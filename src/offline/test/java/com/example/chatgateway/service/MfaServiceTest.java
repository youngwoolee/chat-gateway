package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chatgateway.client.pfc.MockPfcClient;
import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.pfc.PfcClient.MfaResult;
import com.example.chatgateway.config.MfaProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.LocalAuthorityStore;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MfaServiceTest {
  final LocalAuthorityStore store = new LocalAuthorityStore();
  final Clock clock = mock(Clock.class);
  final PfcClient pfc = spy(new MockPfcClient(clock));
  final ObjectMapper mapper = new ObjectMapper();
  final Session session =
      new Session("sid", "member", "MEMBER", "channel", "ACTIVE", 0, 300000, null, "thread");
  final String context = Credentials.random();
  final SessionSettings settings =
      new SessionSettings(
          Duration.ofMinutes(5),
          Duration.ofMinutes(10),
          "example.com",
          "/",
          "out",
          "in",
          Set.of("apply"));
  final MfaProperties policy =
      new MfaProperties(
          Duration.ofMinutes(10), Duration.ofMinutes(2), 8, 4096, Set.of("FINANCE_CA", "SHINHAN"));
  final ChannelAccessService access = mock(ChannelAccessService.class);
  MfaService service;

  @BeforeEach
  void setup() {
    when(clock.millis()).thenReturn(1000L);
    store.create(session, null);
    store.saveGrant(
        new AuthorityStore.ExecutionGrant(
            Credentials.hash(context), "member", "MEMBER", "channel", "thread", 600000));
    service = new MfaService(store, pfc, settings, policy, mapper, clock, access);
  }

  MfaService.Registration registration(String key, String content) {
    return new MfaService.Registration(
        key,
        "op",
        "v1",
        "apply",
        Credentials.hash("action"),
        "synthetic-test",
        null,
        "code",
        "usage",
        content);
  }

  String register(String key) {
    return service.register(context, registration(key, "content")).authReqId();
  }

  MfaResult result(String id) {
    return new MfaResult(id, "local:" + id, null, "SHINHAN");
  }

  List<String> resume(String key, List<MfaResult> results) {
    return service.verifyResume(session, "thread", key, "/api/chat", "body".getBytes(), results);
  }

  @Test
  void revokedChannelPermissionCannotStartMfaUsingExistingContext() {
    doThrow(GatewayException.denied()).when(access).require("channel", "member", true);
    assertThatThrownBy(() -> register("denied")).hasMessage("PERMISSION_DENIED");
    verify(pfc, never()).start(any());
  }

  @Test
  void registrationSupportsMultipleRequestsAndIdempotentSdk() {
    var first = service.register(context, registration("key", "content"));
    assertThat(service.register(context, registration("key", "content"))).isEqualTo(first);
    assertThat(register("second")).isNotEqualTo(first.authReqId());
    assertThatThrownBy(() -> service.register(context, registration("key", "changed")))
        .hasMessage("CONFLICT");
    verify(pfc, times(2)).start(any());
    assertThat(first.sdkInput()).doesNotContain("synthetic-test");
  }

  @Test
  void invalidItemDoesNotConsumeAnyAndFailedIdCanRetry() {
    String a = register("a"), b = register("b");
    assertThatThrownBy(
            () -> resume("resume", List.of(result(a), new MfaResult(b, "bad", null, "SHINHAN"))))
        .isInstanceOfSatisfying(
            GatewayException.class,
            e -> {
              assertThat(e.code()).isEqualTo(403);
              assertThat(e.failedAuthReqIds()).containsExactly(b);
            });
    assertThat(store.findResume("sid", "resume")).isNull();
    assertThat(resume("resume", List.of(result(a), result(b)))).containsExactly(a, b);
  }

  @Test
  void sameResumeReusesReceiptWithoutPlatformCallAndCannotChangeBodyOrKey() {
    var results = List.of(result(register("a")));
    resume("key", results);
    long until = store.findResume("sid", "key").expiresAt();
    when(clock.millis()).thenReturn(2000L);
    resume("key", results);
    verify(pfc, times(1)).verify(any(), any());
    assertThat(store.findResume("sid", "key").expiresAt()).isEqualTo(until);
    assertThatThrownBy(
            () ->
                service.verifyResume(
                    session, "thread", "key", "/api/chat", "changed".getBytes(), results))
        .hasMessage("CONFLICT");
    assertThatThrownBy(() -> resume("other", results)).hasMessage("CONFLICT");
    when(clock.millis()).thenReturn(until);
    assertThatThrownBy(() -> resume("key", results)).hasMessage("MFA_EXPIRED");
  }

  @Test
  void wrongCustomerComparisonOrThreadNeverConsumes() {
    String id = register("a");
    var request = store.authRequest(id);
    doReturn(
            new PfcClient.VerifiedTx(
                "other", id, "local:" + id, null, "SHINHAN", request.comparison(), 200000))
        .when(pfc)
        .verify(any(), any());
    assertThatThrownBy(() -> resume("r", List.of(result(id)))).hasMessage("MFA_INVALID");
    assertThat(store.findResume("sid", "r")).isNull();
    assertThatThrownBy(
            () ->
                service.verifyResume(
                    session, "other-thread", "r", "/api/chat", new byte[0], List.of(result(id))))
        .isInstanceOfSatisfying(
            GatewayException.class, e -> assertThat(e.failedAuthReqIds()).isEmpty());
  }

  @Test
  void concurrentDifferentResumeKeysCanConsumeOnlyOnce() throws Exception {
    var results = List.of(result(register("a")), result(register("b")));
    var start = new CountDownLatch(1);
    var wins = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 8; i++) {
        String key = "key" + i;
        tasks.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    resume(key, results);
                    wins.incrementAndGet();
                  } catch (GatewayException e) {
                    assertThat(e.status()).isEqualTo("CONFLICT");
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var task : tasks) task.get();
    }
    assertThat(wins.get()).isEqualTo(1);
  }

  @Test
  void revocationBetweenVerificationAndConsumptionRejectsWholeBatch() {
    String id = register("a");
    doAnswer(
            inv -> {
              var verified = inv.callRealMethod();
              store.revoke("sid");
              return verified;
            })
        .when(pfc)
        .verify(any(), any());
    assertThatThrownBy(() -> resume("key", List.of(result(id)))).hasMessage("UNAUTHENTICATED");
    assertThat(store.findResume("sid", "key")).isNull();
  }

  @Test
  void storeConflictDoesNotPartiallyConsumeFreshRequest() {
    String used = register("used"), fresh = register("fresh");
    resume("first", List.of(result(used)));
    assertThatThrownBy(() -> resume("batch", List.of(result(fresh), result(used))))
        .hasMessage("CONFLICT");
    assertThat(store.findResume("sid", "batch")).isNull();
    assertThat(resume("fresh-only", List.of(result(fresh)))).containsExactly(fresh);
  }

  @Test
  void guestUnknownMethodAndMissingFinanceTxCannotConsume() {
    String id = register("one");
    var guest =
        new Session("guest", "guest", "GUEST", "channel", "ACTIVE", 0, 300000, null, "thread");
    assertThatThrownBy(
            () ->
                service.verifyResume(
                    guest, "thread", "key", "/api/chat", new byte[0], List.of(result(id))))
        .hasMessage("MFA_INVALID");
    for (String method : List.of("UNKNOWN", "FINANCE_CA"))
      assertThatThrownBy(
              () -> resume("key", List.of(new MfaResult(id, "local:" + id, null, method))))
          .hasMessage("MFA_INVALID");
    assertThat(store.findResume("sid", "key")).isNull();
  }

  @Test
  void expiryOrProviderOutageNeverConsumes() {
    String id = register("a");
    doThrow(GatewayException.unavailable()).when(pfc).verify(any(), any());
    assertThatThrownBy(() -> resume("key", List.of(result(id)))).hasMessage("UNAVAILABLE");
    assertThat(store.findResume("sid", "key")).isNull();
    when(clock.millis()).thenReturn(601000L);
    assertThatThrownBy(() -> resume("key", List.of(result(id)))).hasMessage("MFA_INVALID");
  }
}
