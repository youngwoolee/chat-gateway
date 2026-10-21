package com.example.chatgateway.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.config.SessionServiceProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthRequest;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false", "chat.session-service.credential=session-test-secret",
      "chat.session-service.response-timeout=250ms", "chat.session-service.connect-timeout=250ms",
      "spring.cloud.loadbalancer.cache.enabled=false"
    })
class HttpSessionServiceClientTest {
  private static final MockWebServer SERVER = new MockWebServer();

  static {
    try {
      SERVER.start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.agent-session-service[0].uri",
        () -> SERVER.url("/").toString());
  }

  @AfterAll
  static void stop() throws IOException {
    SERVER.shutdown();
  }

  @Autowired AuthorityStore store;
  @Autowired ObjectMapper mapper;
  @Autowired SessionServiceProperties properties;

  @Autowired
  @Qualifier("sessionServiceRestClientBuilder")
  RestClient.Builder builder;

  private final Session session =
      new Session(
          "hashed-id", "member", "MEMBER", "channel", "ACTIVE", 100, 300100, null, "thread");

  @BeforeEach
  void drainRequests() throws InterruptedException {
    while (SERVER.takeRequest(1, TimeUnit.MILLISECONDS) != null) {}
  }

  @Test
  void discoversServiceAndSendsAuthenticatedAtomicCommand() throws Exception {
    assertThat(store).isInstanceOf(HttpSessionServiceClient.class);
    SERVER.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(mapper.writeValueAsString(session)));
    assertThat(store.touch("hashed-id", 100, 300000, "same-key")).isEqualTo(session);
    var request = SERVER.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getPath()).isEqualTo("/internal/v1/authority/touch-session");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-test-secret");
    assertThat(request.getHeader("Cookie")).isNull();
    assertThat(mapper.readTree(request.getBody().readUtf8()))
        .isEqualTo(
            mapper.readTree(
                "{\"hash\":\"hashed-id\",\"now\":100,\"ttl\":300000,\"activityKey\":\"same-key\"}"));
  }

  @Test
  void onlyExplicitSessionAbsenceBecomesNull() {
    SERVER.enqueue(error(404, "SESSION_NOT_FOUND"));
    assertThat(store.find("missing")).isNull();
    SERVER.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));
    unavailable(() -> store.find("missing"));
    SERVER.enqueue(error(404, "SESSION_NOT_FOUND"));
    unavailable(() -> store.authRequest("missing"));
  }

  @Test
  void preservesBusinessConflictsButNotInfrastructureErrors() {
    SERVER.enqueue(error(409, "CONFLICT"));
    assertThatThrownBy(() -> store.replaceGuest("hashed-id", "key", session, 100))
        .isInstanceOfSatisfying(
            GatewayException.class,
            e -> {
              assertThat(e.code()).isEqualTo(409);
              assertThat(e.status()).isEqualTo("CONFLICT");
            });
    for (int status : List.of(401, 403, 500, 503)) {
      SERVER.enqueue(error(status, "SERVICE_FAILURE"));
      unavailable(() -> store.find("hashed-id"));
    }
  }

  @Test
  void readinessRequiresAcknowledgement() throws Exception {
    SERVER.enqueue(new MockResponse().setResponseCode(204));
    store.checkAvailable();
    var ready = SERVER.takeRequest(1, TimeUnit.SECONDS);
    assertThat(ready.getMethod()).isEqualTo("GET");
    assertThat(ready.getPath()).endsWith("/ready");
    SERVER.enqueue(new MockResponse().setResponseCode(200));
    unavailable(() -> store.checkAvailable());
  }

  @Test
  void neverRetriesUncertainMutationOrFollowsRedirect() throws Exception {
    int before = SERVER.getRequestCount();
    SERVER.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    unavailable(() -> store.consumeAuth(command()));
    assertThat(SERVER.takeRequest(1, TimeUnit.SECONDS).getPath())
        .endsWith("/consume-auth-requests");
    assertThat(SERVER.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
    assertThat(SERVER.getRequestCount() - before).isEqualTo(1);
    SERVER.enqueue(
        new MockResponse().setResponseCode(307).setHeader("Location", SERVER.url("/unexpected")));
    unavailable(() -> store.consumeAuth(command()));
    assertThat(SERVER.takeRequest(1, TimeUnit.SECONDS).getPath())
        .endsWith("/consume-auth-requests");
    assertThat(SERVER.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void timeoutsDiscoveryAndMalformedRepliesFailClosed() {
    SERVER.enqueue(new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("{}"));
    unavailable(() -> store.find("hashed-id"));
    for (String body : List.of("null", "{}", "[]", "not-json")) {
      SERVER.enqueue(new MockResponse().setBody(body));
      unavailable(() -> store.find("hashed-id"));
    }
    var missing =
        new HttpSessionServiceClient(
            builder.clone().baseUrl("http://no-such-session-service").build(), properties, mapper);
    unavailable(() -> missing.find("hashed-id"));
  }

  @Test
  void credentialIsRequiredBeforeNetworkAccess() {
    var noCredential =
        new SessionServiceProperties(
            "agent-session-service", "", Duration.ofSeconds(1), Duration.ofSeconds(1));
    var client =
        new HttpSessionServiceClient(
            builder.clone().baseUrl("http://agent-session-service").build(), noCredential, mapper);
    int before = SERVER.getRequestCount();
    unavailable(() -> client.find("hashed-id"));
    assertThat(SERVER.getRequestCount()).isEqualTo(before);
  }

  @Test
  void promotionAndConsumptionAreSingleCommandsWithAllPreconditions() throws Exception {
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(session)));
    assertThat(store.replaceGuest("guest-hash", "replacement-key", session, 100))
        .isEqualTo(session);
    var promotion = SERVER.takeRequest(1, TimeUnit.SECONDS);
    var json = mapper.readTree(promotion.getBody().readUtf8());
    assertThat(promotion.getPath()).endsWith("/replace-guest");
    assertThat(json.path("hash").asText()).isEqualTo("guest-hash");
    assertThat(json.has("fingerprint")).isFalse();
    assertThat(json.path("replacement").path("idHash").asText()).isEqualTo("hashed-id");
    var receipt =
        new AuthorityStore.ResumeReceipt(
            "hashed-id", "resume", "fingerprint", List.of("ar_one", "ar_two"), 120100);
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(receipt)));
    assertThat(store.consumeAuth(command())).isEqualTo(receipt);
    var consume = SERVER.takeRequest(1, TimeUnit.SECONDS);
    json = mapper.readTree(consume.getBody().readUtf8());
    assertThat(consume.getPath()).endsWith("/consume-auth-requests");
    assertThat(json.path("sessionHash").asText()).isEqualTo("hashed-id");
    assertThat(json.path("proofs").size()).isEqualTo(2);
    assertThat(json.path("fingerprint").asText()).isEqualTo("fingerprint");
    assertThat(SERVER.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void rejectsWrongSessionAndAuthenticationRequestIdentity() throws Exception {
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(session)));
    unavailable(() -> store.find("different-hash"));
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(authRequest())));
    unavailable(() -> store.authRequest("different-request"));
  }

  @Test
  void resumeLookupRequiresExplicitAbsenceAndConsumptionCannotReturnOtherIds() throws Exception {
    SERVER.enqueue(error(404, "RESUME_NOT_FOUND"));
    assertThat(store.findResume("hashed-id", "resume")).isNull();
    SERVER.enqueue(error(404, "NOT_FOUND"));
    unavailable(() -> store.findResume("hashed-id", "resume"));
    var wrong =
        new AuthorityStore.ResumeReceipt(
            "hashed-id", "resume", "fingerprint", List.of("ar_other"), 120100);
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(wrong)));
    unavailable(() -> store.consumeAuth(command()));
  }

  @Test
  void consumptionErrorsCanOnlyExposeSubmittedRequestIds() {
    SERVER.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setBody(
                "{\"error\":{\"code\":403,\"status\":\"MFA_INVALID\",\"failedAuthReqIds\":[\"ar_one\"]}}"));
    assertThatThrownBy(() -> store.consumeAuth(command()))
        .isInstanceOfSatisfying(
            GatewayException.class,
            e -> assertThat(e.failedAuthReqIds()).containsExactly("ar_one"));
    SERVER.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setBody(
                "{\"error\":{\"code\":403,\"status\":\"MFA_INVALID\",\"failedAuthReqIds\":[\"ar_other\"]}}"));
    unavailable(() -> store.consumeAuth(command()));
  }

  @Test
  void typedRequestsPreserveCreationAndSdkContracts() throws Exception {
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(session)));
    assertThat(store.create(session, null)).isEqualTo(session);
    var create = mapper.readTree(SERVER.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
    assertThat(create.has("creationKey")).isTrue();
    assertThat(create.path("creationKey").isNull()).isTrue();
    assertThat(create.path("session"))
        .isEqualTo(mapper.readTree(mapper.writeValueAsString(session)));

    var auth = authRequest();
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(auth)));
    assertThat(store.registerAuth(auth, 100)).isEqualTo(auth);
    var register = mapper.readTree(SERVER.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
    assertThat(register.path("request"))
        .isEqualTo(mapper.readTree(mapper.writeValueAsString(auth)));
    assertThat(register.path("now").asLong()).isEqualTo(100);

    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(auth)));
    assertThat(store.saveSdkInput("ar_one", "sdk", 100)).isEqualTo(auth);
    var sdk = mapper.readTree(SERVER.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
    assertThat(sdk)
        .isEqualTo(mapper.readTree("{\"id\":\"ar_one\",\"sdkInput\":\"sdk\",\"now\":100}"));
  }

  @Test
  void missingFieldsAndNullPrimitivesCannotBecomeAuthenticatedDefaults() throws Exception {
    for (String field : List.of("subject", "guestSubject", "expiresAt")) {
      var json = mapper.valueToTree(session);
      ((ObjectNode) json).remove(field);
      SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(json)));
      unavailable(() -> store.find("hashed-id"));
    }
    var json = (ObjectNode) mapper.valueToTree(session);
    json.putNull("expiresAt");
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(json)));
    unavailable(() -> store.find("hashed-id"));
    json = (ObjectNode) mapper.valueToTree(authRequest());
    json.putNull("subject");
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(json)));
    unavailable(() -> store.authRequest("ar_one"));
  }

  @Test
  void restartUsesOneAtomicCommandAndRejectsUnrelatedSession() throws Exception {
    var command =
        new AuthorityStore.RestartSession(
            "old-hash", "restart-key", "thread", "hashed-id", 100, 300000);
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(session)));
    assertThat(store.restart(command)).isEqualTo(session);
    var request = SERVER.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getPath()).endsWith("/restart-session");
    assertThat(mapper.readTree(request.getBody().readUtf8()))
        .isEqualTo(mapper.readTree(mapper.writeValueAsString(command)));
    SERVER.enqueue(new MockResponse().setBody(mapper.writeValueAsString(session)));
    unavailable(
        () ->
            store.restart(
                new AuthorityStore.RestartSession(
                    "other", "key", "thread", "unrelated", 100, 300000)));
  }

  private AuthorityStore.ConsumeAuth command() {
    return new AuthorityStore.ConsumeAuth(
        "hashed-id",
        "resume",
        "fingerprint",
        "thread",
        List.of(
            new AuthorityStore.Proof("ar_one", "proof1", 120100),
            new AuthorityStore.Proof("ar_two", "proof2", 120100)),
        100);
  }

  private AuthRequest authRequest() {
    return new AuthRequest(
        "ar_one",
        "key",
        "member",
        "channel",
        "thread",
        "op",
        "v1",
        "apply",
        "hash",
        new AuthRequest.Comparison("test", null, "code", "usage", "content"),
        300100,
        "sdk");
  }

  private MockResponse error(int code, String status) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"error\":{\"code\":"
                + code
                + ",\"status\":\""
                + status
                + "\",\"message\":\"internal details must not leak\"}}");
  }

  private void unavailable(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            GatewayException.class,
            e -> {
              assertThat(e.code()).isEqualTo(503);
              assertThat(e.getMessage()).isEqualTo("UNAVAILABLE");
            });
  }
}
