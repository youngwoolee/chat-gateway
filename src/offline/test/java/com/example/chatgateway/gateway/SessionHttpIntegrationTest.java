package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("offline")
class SessionHttpIntegrationTest {
  static final String CHANNEL = "00000000-0000-0000-0000-000000000001";
  static final MockWebServer BACKEND = new MockWebServer();

  static {
    try {
      BACKEND.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("chat.channels.allowed.other", () -> "PFC");
    r.add("chat.backend.base-url", () -> BACKEND.url("/").toString());
  }

  @LocalServerPort int port;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuthorityStore store;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.example.chatgateway.client.pfc.PfcClient platform;

  @Autowired ObjectMapper mapper;
  @MockitoBean Clock clock;
  HttpProbe client;
  String origin;

  @BeforeEach
  void setup() {
    when(clock.millis()).thenAnswer(invocation -> System.currentTimeMillis());
    origin = "http://localhost:" + port;
    client = new HttpProbe(origin);
  }

  @AfterAll
  static void close() throws Exception {
    BACKEND.shutdown();
  }

  String seed(boolean member, long expires) {
    String sid = Credentials.random();
    store.create(
        new Session(
            Credentials.hash(sid),
            member ? "local-member" : "guest-demo",
            member ? "MEMBER" : "GUEST",
            CHANNEL,
            "ACTIVE",
            System.currentTimeMillis(),
            expires,
            null,
            "thread"),
        null);
    return sid;
  }

  HttpProbe.Request post(String path) {
    return client
        .post()
        .uri(path)
        .header("Origin", origin)
        .header("Idempotency-Key", UUID.randomUUID().toString());
  }

  HttpProbe.Request promotion(String sid, String key) {
    return post("/api/chat")
        .header("X-Requested-Login", "true")
        .headers(h -> h.set("Idempotency-Key", key))
        .cookie(SessionCookies.NAME, sid)
        .cookie("PlatformSystemCode_O", "DEMO")
        .cookie("PlatformAuthentication_O", "demo-ticket")
        .cookie("PlatformVerify_O", "demo-proof")
        .bodyValue(Map.of("threadId", "thread"));
  }

  /** FE A1–A4: 진입/복원은 session→messages, GET은 비연장, chat 재전송 키는 그대로 전달한다. */
  @Test
  void e2eA1ToA4GuestEntryReloadAndChatRetry() throws Exception {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String thread = UUID.randomUUID().toString();
    var created =
        post("/api/auth/session?channelId=" + CHANNEL + "&threadId=" + thread)
            .exchange()
            .expectStatus()
            .isOk();
    String sid = created.getResponseCookies().getFirst(SessionCookies.NAME).getValue();
    Session first = store.find(Credentials.hash(sid));
    assertThat(first.userType()).isEqualTo("GUEST");
    assertThat(first.threadId()).isEqualTo(thread);
    BACKEND.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("[]"));
    client
        .get()
        .uri("/api/chat/threads/" + thread + "/messages")
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("[]");
    var read = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(read.getHeader("X-Authenticated-User-Type")).isEqualTo("GUEST");
    assertThat(read.getHeader("X-Authenticated-Guest-Id")).isEqualTo(first.subject());
    assertThat(read.getHeader("X-Auth-Context")).isNull();
    assertThat(store.find(first.idHash()).expiresAt()).isEqualTo(first.expiresAt());
    String key = UUID.randomUUID().toString();
    for (int i = 1; i <= 2; i++) {
      when(clock.millis()).thenReturn(now + i * 1000);
      BACKEND.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody("data: done\n\n"));
      post("/api/chat")
          .headers(h -> h.set("Idempotency-Key", key))
          .cookie(SessionCookies.NAME, sid)
          .bodyValue(Map.of("threadId", thread, "text", "hello"))
          .exchange()
          .expectStatus()
          .isOk()
          .getResponseBody();
      assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getHeader("Idempotency-Key"))
          .isEqualTo(key);
    }
    assertThat(store.find(first.idHash()).expiresAt()).isEqualTo(now + 301000);
    when(clock.millis()).thenReturn(now + 3000);
    post("/api/auth/session?threadId=" + thread)
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .doesNotExist("Set-Cookie");
    BACKEND.enqueue(new MockResponse().setBody("[]"));
    client
        .get()
        .uri("/api/chat/threads/" + thread + "/messages")
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    assertThat(store.find(first.idHash()).expiresAt()).isEqualTo(now + 303000);
  }

  /** FE A5/B3: 현재 계약은 JSON threadId와 키가 필수이며 query-only 요청은 지원하지 않는다. */
  @Test
  void e2eA5RestartRequiresBodyAndKey() throws Exception {
    String sid = seed(false, clock.millis() + 60000);
    Session before = store.find(Credentials.hash(sid));
    post("/api/auth/session/restart?channelId=" + CHANNEL + "&threadId=new-thread")
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    client
        .post()
        .uri("/api/auth/session/restart")
        .header("Origin", origin)
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "new-thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    assertThat(store.find(before.idHash())).isEqualTo(before);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  /** FE B4: 자연 만료된 회원은 _O로 새 SID를 발급받되 기존 thread를 요청할 수 있다. */
  @Test
  void e2eB4ExpiredMemberReauthenticatesThenReadsSameThread() throws Exception {
    String sid = seed(true, clock.millis() - 1);
    var response =
        promotion(sid, "promotion")
            .uri("/api/auth/session?channelId=" + CHANNEL + "&threadId=thread")
            .exchange()
            .expectStatus()
            .isOk();
    String next = response.getResponseCookies().getFirst(SessionCookies.NAME).getValue();
    assertThat(next).isNotEqualTo(sid);
    assertThat(store.find(Credentials.hash(next)).userType()).isEqualTo("MEMBER");
    assertThat(store.find(Credentials.hash(next)).threadId()).isEqualTo("thread");
    BACKEND.enqueue(new MockResponse().setBody("[]"));
    client
        .get()
        .uri("/api/chat/threads/thread/messages")
        .cookie(SessionCookies.NAME, next)
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    var upstream = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(upstream.getHeader("X-Authenticated-User-Id")).isEqualTo("local-member");
    assertThat(upstream.getHeader("X-Authenticated-Guest-Id")).isNull();
    assertThat(upstream.getHeader("X-Auth-Context")).isNotBlank();
  }

  /** FE C4: 로그인 취소는401이며 게스트 신원·기한은 유지된다. */
  @Test
  void e2eC4CancelledLoginPreservesGuestWithoutBackendCall() throws Exception {
    String sid = seed(false, clock.millis() + 60000);
    Session before = store.find(Credentials.hash(sid));
    post("/api/chat")
        .header("X-Requested-Login", "true")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .doesNotExist("Set-Cookie")
        .expectBody()
        .jsonPath("$.error.status")
        .isEqualTo("UNAUTHENTICATED");
    assertThat(store.find(before.idHash())).isEqualTo(before);
    org.mockito.Mockito.verifyNoInteractions(platform);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  /** FE C6: 복귀 session POST 및 일반 chat은 _O가 있어도 회원 전환하지 않는다. */
  @Test
  void e2eC6PlatformCookieAloneDoesNotPromoteGuest() throws Exception {
    String sid = seed(false, clock.millis() + 60000);
    promotion(sid, "session-no-promotion")
        .uri("/api/auth/session?threadId=thread")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.userType")
        .isEqualTo("GUEST");
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    promotion(sid, "chat-no-promotion")
        .headers(h -> h.remove("X-Requested-Login"))
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    var upstream = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(upstream.getHeader("X-Authenticated-User-Type")).isEqualTo("GUEST");
    assertThat(upstream.getHeader("X-Auth-Context")).isNull();
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  /** FE E2/E4/E5: 다른 thread 읽기는 B/E 소유권 검사에 위임하지만 쓰기는 세션 thread만 허용한다. */
  @Test
  void e2eE2E4E5HistoryReadsDelegateOwnershipButWritesRequireCurrentThread() throws Exception {
    String sid = seed(true, clock.millis() + 60000);
    Session before = store.find(Credentials.hash(sid));
    for (int code : new int[] {200, 403, 404}) {
      String body = code == 200 ? "[]" : "{\"businessCode\":\"NOT_OWNED\"}";
      BACKEND.enqueue(new MockResponse().setResponseCode(code).setBody(body));
      client
          .get()
          .uri("/api/chat/threads/previous-thread/messages")
          .cookie(SessionCookies.NAME, sid)
          .exchange()
          .expectStatus()
          .isEqualTo(code)
          .expectBody(String.class)
          .isEqualTo(body);
      assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getPath())
          .isEqualTo("/internal/v1/chat/threads/previous-thread/messages");
    }
    post("/api/chat")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "previous-thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    assertThat(store.find(before.idHash())).isEqualTo(before);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void channelMismatchRejectsReadsAndActivityWithoutSideEffects() throws Exception {
    String sid = seed(false, System.currentTimeMillis() + 60000);
    var before = store.find(Credentials.hash(sid));
    org.mockito.Mockito.clearInvocations(platform);
    for (String method : new String[] {"GET", "POST"}) {
      var request =
          method.equals("GET")
              ? client.get().uri("/api/chat/threads?channelId=other")
              : promotion(sid, "promotion").uri("/api/chat?channelId=other");
      if (method.equals("GET")) request.cookie(SessionCookies.NAME, sid);
      request
          .exchange()
          .expectStatus()
          .isForbidden()
          .expectHeader()
          .doesNotExist("Set-Cookie")
          .expectBody()
          .jsonPath("$.error.status")
          .isEqualTo("PERMISSION_DENIED");
    }
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    org.mockito.Mockito.verifyNoInteractions(platform);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void missingAndDuplicateKeysRejectMutationsBeforeExternalCalls() throws Exception {
    String sid = seed(true, System.currentTimeMillis() + 60000);
    var before = store.find(Credentials.hash(sid));
    org.mockito.Mockito.clearInvocations(platform);
    for (String path :
        new String[] {"/api/chat", "/api/chat/threads/thread/tools/tool", "/api/click"}) {
      for (boolean duplicate : new boolean[] {false, true}) {
        var request =
            client
                .post()
                .uri(path)
                .header("Origin", origin)
                .cookie(SessionCookies.NAME, sid)
                .bodyValue(
                    Map.of(
                        "threadId",
                        "thread",
                        "state",
                        "selected",
                        "scenarioId",
                        "limit",
                        "component",
                        "next",
                        "operationId",
                        "op",
                        "authenticationRequestId",
                        Credentials.random(),
                        "tx",
                        "tx"));
        if (duplicate) request.header("Idempotency-Key", "one").header("Idempotency-Key", "two");
        request
            .exchange()
            .expectStatus()
            .isEqualTo(400)
            .expectHeader()
            .doesNotExist("Set-Cookie")
            .expectBody()
            .jsonPath("$.error.status")
            .isEqualTo("INVALID_ARGUMENT");
      }
    }
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    org.mockito.Mockito.verifyNoInteractions(platform);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void memberSessionAutomaticallyIssuesHomepageCookiesWithoutExtraActivity() {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String sid = seed(true, now + 60000);
    var response =
        post("/api/auth/session")
            .headers(h -> h.set("Idempotency-Key", "homepage"))
            .cookie(SessionCookies.NAME, sid)
            .exchange()
            .expectStatus()
            .isOk();
    var cookies = response.getResponseCookies();
    assertThat(cookies).hasSize(3);
    for (String name :
        new String[] {"PlatformSystemCode_I", "PlatformAuthentication_I", "PlatformVerify_I"}) {
      var cookie = cookies.getFirst(name);
      assertThat(cookie).isNotNull();
      assertThat(cookie.getDomain()).isEqualTo("shinhancard.com");
      assertThat(cookie.getPath()).isEqualTo("/");
      assertThat(cookie.isSecure()).isTrue();
      assertThat(cookie.isHttpOnly()).isTrue();
    }
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(now + 300000);
    when(clock.millis()).thenReturn(now + 1000);
    post("/api/auth/session")
        .headers(h -> h.set("Idempotency-Key", "homepage"))
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(now + 300000);
    post("/api/auth/session")
        .cookie(SessionCookies.NAME, seed(false, now + 60000))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .doesNotExist("Set-Cookie");
    org.mockito.Mockito.doThrow(com.example.chatgateway.exception.GatewayException.unavailable())
        .when(store)
        .touch(
            org.mockito.ArgumentMatchers.eq(Credentials.hash(sid)),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString());
    post("/api/auth/session")
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .doesNotExist("Set-Cookie");
  }

  @Test
  void homepageEndpointIsRemovedAndReadDoesNotIssueCookies() {
    post("/api/auth/homepage-transfer").exchange().expectStatus().isNotFound();
    client.get().uri("/api/auth/session").exchange().expectStatus().isEqualTo(405);
    post("/api/auth/activity").exchange().expectStatus().isNotFound();
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  @Test
  void homepageIssuanceFailurePreventsChatForwarding() throws Exception {
    org.mockito.Mockito.doThrow(com.example.chatgateway.exception.GatewayException.unavailable())
        .when(platform)
        .homepage(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    post("/api/chat")
        .cookie(SessionCookies.NAME, seed(true, System.currentTimeMillis() + 60000))
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .doesNotExist("Set-Cookie");
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  String registerAuth(String context, String key) throws Exception {
    var response =
        client
            .post()
            .uri("/internal/v1/auth/operations")
            .header("Authorization", "Bearer local-backend-to-gateway")
            .header("X-Auth-Context", context)
            .bodyValue(
                Map.of(
                    "key",
                    key,
                    "operationId",
                    "operation",
                    "version",
                    "v1",
                    "interfaceId",
                    "demo-apply",
                    "actionHash",
                    Credentials.hash("action"),
                    "ssn",
                    "synthetic-only",
                    "code",
                    "code",
                    "usage",
                    "usage",
                    "content",
                    "content"))
            .exchange()
            .expectStatus()
            .isOk();
    var json = mapper.readTree(response.getResponseBody());
    assertThat(json.has("ssn")).isFalse();
    return json.path("authReqId").asText();
  }

  String results(String... ids) throws Exception {
    var list = new java.util.ArrayList<Map<String, String>>();
    for (String id : ids)
      list.add(Map.of("authReqId", id, "tranID", "local:" + id, "method", "SHINHAN"));
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(list));
  }

  @Test
  void resumeVerifiesAllBeforeSseStripsSpoofedHeadersAndSupportsIdenticalRetry() throws Exception {
    String sid = seed(true, clock.millis() + 300000);
    String context = grant("thread");
    String a = registerAuth(context, "first-" + UUID.randomUUID());
    String b = registerAuth(context, "second-" + UUID.randomUUID());
    String encoded = results(a, b);
    String key = UUID.randomUUID().toString();
    String body = "{\"threadId\":\"thread\",\"resume\":{\"value\":1}}";
    for (int i = 0; i < 2; i++) {
      BACKEND.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody("data: done\n\n"));
      post("/api/chat")
          .headers(h -> h.set("Idempotency-Key", key))
          .cookie(SessionCookies.NAME, sid)
          .header("X-Mfa-Result", encoded)
          .header("X-Verified-Auth-Requests", "attacker")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .isOk()
          .getResponseBody();
      var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
      assertThat(forwarded.getPath()).isEqualTo("/internal/v1/chat");
      assertThat(forwarded.getHeader("X-Requested-Login")).isNull();
      assertThat(forwarded.getHeader("X-Verified-Login")).isNull();
      assertThat(forwarded.getHeader("X-Mfa-Result")).isNull();
      assertThat(forwarded.getHeader("X-Verified-Auth-Requests")).isEqualTo(a + ", " + b);
      assertThat(forwarded.getBody().readUtf8()).isEqualTo(body);
    }
    org.mockito.Mockito.verify(platform, org.mockito.Mockito.times(2))
        .verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    post("/api/chat")
        .cookie(SessionCookies.NAME, sid)
        .header("X-Mfa-Result", encoded)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void failedItemReturns403WithIdAndNoPartialConsumptionOrBackendCall() throws Exception {
    String sid = seed(true, clock.millis() + 300000), context = grant("thread");
    String a = registerAuth(context, "first-" + UUID.randomUUID());
    String b = registerAuth(context, "second-" + UUID.randomUUID());
    var values =
        java.util.List.of(
            Map.of("authReqId", a, "tranID", "local:" + a, "method", "SHINHAN"),
            Map.of("authReqId", b, "tranID", "bad", "method", "SHINHAN"));
    String bad =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(values));
    String key = UUID.randomUUID().toString();
    var response =
        post("/api/chat")
            .headers(h -> h.set("Idempotency-Key", key))
            .cookie(SessionCookies.NAME, sid)
            .header("X-Mfa-Result", bad)
            .bodyValue(Map.of("threadId", "thread"))
            .exchange()
            .expectStatus()
            .isForbidden();
    var error = mapper.readTree(response.getResponseBody()).path("error");
    assertThat(error.path("failedAuthReqIds").get(0).asText()).isEqualTo(b);
    assertThat(store.findResume(Credentials.hash(sid), key)).isNull();
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat")
        .headers(h -> h.set("Idempotency-Key", key))
        .cookie(SessionCookies.NAME, sid)
        .header("X-Mfa-Result", results(a, b))
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void clickExtendsOnlySessionOnceWithoutPfcAndCannotReviveExpiredSession() throws Exception {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String sid = seed(true, now + 10000), context = grant("thread");
    String id = registerAuth(context, "click-" + UUID.randomUUID());
    long expires = store.authRequest(id).expiresAt();
    org.mockito.Mockito.clearInvocations(platform);
    String key = UUID.randomUUID().toString();
    for (int i = 0; i < 2; i++) {
      when(clock.millis()).thenReturn(now + i * 1000);
      post("/api/click")
          .headers(h -> h.set("Idempotency-Key", key))
          .cookie(SessionCookies.NAME, sid)
          .bodyValue(
              Map.of(
                  "threadId",
                  "thread",
                  "state",
                  "selected",
                  "scenarioId",
                  "limit",
                  "component",
                  Map.of("id", "next"),
                  "futureField",
                  "preserved"))
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .doesNotExist("Set-Cookie");
      assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    }
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(now + 300000);
    assertThat(store.authRequest(id).expiresAt()).isEqualTo(expires);
    org.mockito.Mockito.verifyNoInteractions(platform);
    when(clock.millis()).thenReturn(now + 300000);
    post("/api/click")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(
            Map.of(
                "threadId",
                "thread",
                "state",
                "selected",
                "scenarioId",
                "limit",
                "component",
                Map.of("id", "next"),
                "futureField",
                "preserved"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void clickRejectsInvalidInputAndWrongThreadBeforeBackendOrExtension() throws Exception {
    String sid = seed(true, clock.millis() + 60000);
    var before = store.find(Credentials.hash(sid));
    for (String body :
        new String[] {
          "{\"threadId\":\"thread\"}",
          "{\"threadId\":\"thread\",\"scenarioId\":\"s\",\"state\":\"\",\"component\":{}}"
        }) {
      post("/api/click")
          .cookie(SessionCookies.NAME, sid)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .isEqualTo(400);
    }
    post("/api/click")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(
            Map.of("threadId", "other", "scenarioId", "s", "state", "step1", "component", "button"))
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  @Test
  void clickRecordsGatewayJsonWithoutCallingBackend() throws Exception {
    String sid = seed(false, clock.millis() + 60000);
    var logger = (Logger) LoggerFactory.getLogger("AUDIT");
    var logs = new ListAppender<ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      post("/api/click")
          .cookie(SessionCookies.NAME, sid)
          .header("X-Auth-Context", "spoofed")
          .bodyValue(
              Map.of(
                  "threadId",
                  "thread",
                  "scenarioId",
                  "s",
                  "state",
                  "step1",
                  "component",
                  "button",
                  "secret",
                  "must-not-log"))
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .exists("X-Chat-Auth-Expires-At")
          .expectBody()
          .jsonPath("$.status")
          .isEqualTo("OK");
      assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
      assertThat(logs.list).hasSize(1);
      var async = (AsyncAppender) logger.getAppender("KAFKA_JSON");
      var file = (RollingFileAppender<ILoggingEvent>) async.getAppender("KAFKA_JSON_FILE");
      var envelope = mapper.readTree(file.getEncoder().encode(logs.list.getFirst()));
      assertThat(envelope.path("logger_name").asText()).isEqualTo("AUDIT");
      assertThat(envelope.has("@timestamp")).isTrue();
      assertThat(envelope.path("level").asText()).isEqualTo("INFO");
      var event = envelope.path("message");
      assertThat(event.isObject()).isTrue();
      assertThat(event.path("name").asText()).isEqualTo("click");
      assertThat(event.path("sessionId").asText()).isEqualTo("thread");
      assertThat(event.path("userId").isNull()).isTrue();
      assertThat(event.path("event").asText()).isEqualTo("chatbot_click");
      assertThat(event.path("threadId").asText()).isEqualTo("thread");
      assertThat(event.path("userType").asText()).isEqualTo("GUEST");
      assertThat(event.path("state").asText()).isEqualTo("step1");
      assertThat(event.path("component").asText()).isEqualTo("button");
      assertThat(event.has("secret")).isFalse();
      assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain(sid, "spoofed");
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void clickWritesLegacyCollectorEnvelopeToAuditFile() throws Exception {
    String sid = seed(true, clock.millis() + 60000);
    String key = UUID.randomUUID().toString();
    post("/api/click")
        .headers(headers -> headers.set("Idempotency-Key", key))
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(
            Map.of(
                "threadId",
                "thread",
                "scenarioId",
                "scenario",
                "state",
                "step",
                "component",
                "button",
                "CLNN",
                "spoofed-customer",
                "buttonInfo",
                Map.of(
                    "buttonType",
                    "LINK",
                    "buttonNm",
                    "카드\n안내",
                    "buttonUrl",
                    "/cards",
                    "secret",
                    "must-not-log")))
        .exchange()
        .expectStatus()
        .isOk();
    var logger = (Logger) LoggerFactory.getLogger("AUDIT");
    assertThat(logger.isAdditive()).isFalse();
    var async = (AsyncAppender) logger.getAppender("KAFKA_JSON");
    assertThat(async.isNeverBlock()).isFalse();
    var file = (RollingFileAppender<ILoggingEvent>) async.getAppender("KAFKA_JSON_FILE");
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              String line =
                  Files.readAllLines(Path.of(file.getFile())).stream()
                      .filter(value -> value.contains(key))
                      .findFirst()
                      .orElseThrow();
              var envelope = mapper.readTree(line);
              assertThat(envelope.path("logger_name").asText()).isEqualTo("AUDIT");
              var message = envelope.path("message");
              assertThat(message.path("name").asText()).isEqualTo("click");
              assertThat(message.path("chatbot").asText()).isEqualTo("new-chatbot");
              assertThat(message.path("userId").asText()).isEqualTo("local-member");
              assertThat(message.path("login").asBoolean()).isTrue();
              assertThat(message.path("sessionId").asText()).isEqualTo("thread");
              assertThat(message.path("buttonInfo").path("buttonNm").asText()).isEqualTo("카드\n안내");
              assertThat(message.path("buttonInfo").path("phoneNumber").isNull()).isTrue();
              assertThat(line).doesNotContain(sid, "must-not-log", "spoofed-customer");
            });
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void malformedButtonInfoDoesNotExtendSession() {
    String sid = seed(false, clock.millis() + 60000);
    var before = store.find(Credentials.hash(sid));
    for (Object button : new Object[] {"button", Map.of("buttonType", 123)}) {
      post("/api/click")
          .cookie(SessionCookies.NAME, sid)
          .bodyValue(
              Map.of(
                  "threadId",
                  "thread",
                  "scenarioId",
                  "s",
                  "state",
                  "s",
                  "component",
                  "button",
                  "buttonInfo",
                  button))
          .exchange()
          .expectStatus()
          .isEqualTo(400);
    }
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
  }

  @Test
  void oldMfaEndpointsAreGoneAndVerifiedHeaderCannotBeSpoofed() throws Exception {
    post("/api/auth/mfa").bodyValue(Map.of()).exchange().expectStatus().isNotFound();
    client.get().uri("/internal/v1/auth/operations/old").exchange().expectStatus().isNotFound();
    String sid = seed(true, clock.millis() + 300000);
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat")
        .cookie(SessionCookies.NAME, sid)
        .header("X-Verified-Auth-Requests", "attacker")
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getHeader("X-Verified-Auth-Requests"))
        .isNull();
  }

  String grant(String thread) {
    String context = Credentials.random();
    store.saveGrant(
        new AuthorityStore.ExecutionGrant(
            Credentials.hash(context),
            "local-member",
            "MEMBER",
            CHANNEL,
            thread,
            clock.millis() + 600000));
    return context;
  }

  @Test
  void promotionSendsTrustedGuestAndMemberDirectlyToChatBeforeSseBody() throws Exception {
    String sid = seed(false, System.currentTimeMillis() + 300000);
    String guest = store.find(Credentials.hash(sid)).subject();
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: done\n\n")
            .setBodyDelay(1, TimeUnit.SECONDS));
    var response =
        promotion(sid, "promotion")
            .headers(h -> h.set("X-Authenticated-Guest-Id", "spoofed"))
            .exchange()
            .expectStatus()
            .isOk();
    assertPromotionHeaders(sid, response);
    assertThat(response.getResponseCookies().getFirst("PlatformAuthentication_I")).isNotNull();
    var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(forwarded.getPath()).isEqualTo("/internal/v1/chat");
    assertThat(forwarded.getHeader("X-Requested-Login")).isNull();
    assertThat(forwarded.getHeader("X-Verified-Login")).isNull();
    assertThat(forwarded.getHeader("X-Authenticated-Guest-Id")).isEqualTo(guest);
    assertThat(forwarded.getHeader("X-Authenticated-User-Type")).isEqualTo("MEMBER");
    assertThat(response.getResponseBody())
        .isEqualTo("data: done\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String newSid = response.getResponseCookies().getFirst(SessionCookies.NAME).getValue();
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat")
        .cookie(SessionCookies.NAME, newSid)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getHeader("X-Authenticated-Guest-Id"))
        .isEqualTo(guest);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  void assertPromotionHeaders(String oldSid, HttpProbe.Reply response) {
    var cookies = response.getResponseCookies();
    String newSid = cookies.getFirst(SessionCookies.NAME).getValue();
    assertThat(newSid).isNotEqualTo(oldSid);
    assertThat(store.find(Credentials.hash(oldSid)).state()).isEqualTo("REVOKED");
    var member = store.find(Credentials.hash(newSid));
    assertThat(member.member()).isTrue();
    for (String name :
        new String[] {"PlatformSystemCode_O", "PlatformAuthentication_O", "PlatformVerify_O"})
      assertThat(cookies.getFirst(name).getMaxAge()).isEqualTo(Duration.ZERO);
    assertThat(response.response.headers().firstValue("X-Chat-Auth-Expires-At").orElseThrow())
        .isEqualTo(Long.toString(member.expiresAt()));
    assertThat(
            Long.parseLong(response.response.headers().firstValue("X-Server-Time").orElseThrow()))
        .isPositive();
  }

  @Test
  void upstreamFailureAfterPromotionPreservesRotatedCookie() throws Exception {
    String sid = seed(false, System.currentTimeMillis() + 300000);
    BACKEND.enqueue(
        new MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST));
    var response = promotion(sid, "promotion").exchange().expectStatus().isEqualTo(503);
    assertPromotionHeaders(sid, response);
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/internal/v1/chat");
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void legacyAuthenticationEndpointsAreNotExposed() {
    for (String path : new String[] {"/api/auth/session/refresh", "/api/auth/session/recover"}) {
      post(path).exchange().expectStatus().isNotFound();
    }
    client.get().uri("/internal/.well-known/jwks.json").exchange().expectStatus().isNotFound();
  }

  @Test
  void businessExecutionEndpointIsNotExposed() {
    client
        .post()
        .uri("/adapter/demo-apply")
        .header("Authorization", "Bearer local-backend-to-gateway")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void restartPreservesMemberWithoutPlatformAndReplaysWithEitherCookie() {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String oldSid = seed(true, now + 60000);
    String key = "restart-" + UUID.randomUUID();
    var first =
        post("/api/auth/session/restart")
            .headers(h -> h.set("Idempotency-Key", key))
            .cookie(SessionCookies.NAME, oldSid)
            .cookie("PlatformVerify_O", "ignored")
            .bodyValue(Map.of("threadId", "new-thread"))
            .exchange()
            .expectStatus()
            .isOk();
    String newSid = first.getResponseCookies().getFirst(SessionCookies.NAME).getValue();
    Session replacement = store.find(Credentials.hash(newSid));
    assertThat(newSid).isNotEqualTo(oldSid);
    assertThat(replacement.subject()).isEqualTo("local-member");
    assertThat(replacement.member()).isTrue();
    assertThat(replacement.threadId()).isEqualTo("new-thread");
    assertThat(replacement.guestSubject()).isNull();
    assertThat(store.find(Credentials.hash(oldSid)).state()).isEqualTo("REVOKED");
    assertThat(first.getResponseCookies()).hasSize(1);
    when(clock.millis()).thenReturn(now + 1000);
    for (String cookie : new String[] {oldSid, newSid}) {
      var retry =
          post("/api/auth/session/restart")
              .headers(h -> h.set("Idempotency-Key", key))
              .cookie(SessionCookies.NAME, cookie)
              .bodyValue(Map.of("threadId", "new-thread"))
              .exchange()
              .expectStatus()
              .isOk();
      assertThat(retry.getResponseCookies().getFirst(SessionCookies.NAME).getValue())
          .isEqualTo(newSid);
    }
    assertThat(store.find(Credentials.hash(newSid))).isEqualTo(replacement);
    post("/api/auth/session/restart")
        .headers(h -> h.set("Idempotency-Key", key))
        .cookie(SessionCookies.NAME, newSid)
        .bodyValue(Map.of("threadId", "different"))
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  @Test
  void restartCreatesFreshGuestAndNeverRevivesMissingExpiredOrRevokedSessions() {
    String old = seed(false, clock.millis() + 60000);
    var reply =
        post("/api/auth/session/restart")
            .cookie(SessionCookies.NAME, old)
            .bodyValue(Map.of("threadId", "new-guest-thread"))
            .exchange()
            .expectStatus()
            .isOk();
    String sid = reply.getResponseCookies().getFirst(SessionCookies.NAME).getValue();
    Session fresh = store.find(Credentials.hash(sid));
    assertThat(fresh.subject()).isNotEqualTo("guest-demo");
    assertThat(fresh.member()).isFalse();
    assertThat(fresh.guestSubject()).isNull();
    for (String invalid :
        new String[] {old, seed(true, clock.millis() - 1), Credentials.random()}) {
      post("/api/auth/session/restart")
          .cookie(SessionCookies.NAME, invalid)
          .bodyValue(Map.of("threadId", "another"))
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .doesNotExist("Set-Cookie");
    }
    post("/api/auth/session/restart")
        .bodyValue(Map.of("threadId", "another"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  @Test
  void createsHttpOnlySessionAndGetDoesNotExtend() {
    var response =
        post("/api/auth/session?channelId=" + CHANNEL)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.userType")
            .isEqualTo("GUEST")
            .returnResult();
    var cookie = response.getResponseCookies().getFirst(SessionCookies.NAME);
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getDomain()).isNull();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
    long expiry = store.find(Credentials.hash(cookie.getValue())).expiresAt();
    client
        .get()
        .uri("/api/auth/session")
        .cookie(SessionCookies.NAME, cookie.getValue())
        .exchange()
        .expectStatus()
        .isEqualTo(405);
    assertThat(store.find(Credentials.hash(cookie.getValue())).expiresAt()).isEqualTo(expiry);
  }

  @Test
  void missingOrExpiredSessionDoesNotAuthenticateFromPlatformCookies() {
    for (String sid :
        new String[] {Credentials.random(), seed(false, System.currentTimeMillis() - 1)})
      post("/api/chat?channelId=" + CHANNEL)
          .cookie(SessionCookies.NAME, sid)
          .cookie("PlatformSystemCode_O", "DEMO")
          .cookie("PlatformAuthentication_O", "demo-ticket")
          .cookie("PlatformVerify_O", "demo-proof")
          .bodyValue(Map.of("threadId", "thread"))
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .doesNotExist("Set-Cookie")
          .expectBody()
          .jsonPath("$.error.message")
          .isEqualTo("");
  }

  @Test
  void memberIgnoresMalformedPlatformCookiesAndStripsSpoofedHeaders() throws Exception {
    String sid = seed(true, System.currentTimeMillis() + 300000);
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: done\n\n"));
    String body = "{\"threadId\":\"thread\",\"text\":\"hello\"}";
    post("/api/chat?channelId=" + CHANNEL)
        .cookie(SessionCookies.NAME, sid)
        .cookie("PlatformVerify_O", "malformed")
        .header("X-Authenticated-User-Id", "attacker")
        .header("X-Authenticated-Guest-Id", "attacker")
        .header("X-Operation-Id", "fake")
        .header("X-Auth-Context", "fake")
        .header("Authorization", "Bearer attacker")
        .header("X-User-Id", "attacker")
        .header("X-Channel-Id", "attacker")
        .header("X-Internal-Call", "attacker")
        .header("X-Guest-Id", "attacker")
        .header("X-MFA-Token", "attacker")
        .header("X-Is-Login", "true")
        .header("X-Verified-Login", "true")
        .header("Forwarded", "for=attacker")
        .header("X-Forwarded-For", "attacker")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Chat-Auth-Expires-At")
        .expectBody(String.class)
        .isEqualTo("data: done\n\n");
    var request = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request.getPath()).isEqualTo("/internal/v1/chat?channelId=" + CHANNEL);
    assertThat(request.getBody().readUtf8()).isEqualTo(body);
    assertThat(request.getHeader("X-Authenticated-User-Id")).isEqualTo("local-member");
    assertThat(request.getHeader("X-Authenticated-Guest-Id")).isNull();
    assertThat(request.getHeader("Cookie")).isNull();
    assertThat(request.getHeader("X-Operation-Id")).isNull();
    for (String name :
        new String[] {
          "X-User-Id",
          "X-Channel-Id",
          "X-Internal-Call",
          "X-Guest-Id",
          "X-MFA-Token",
          "X-Is-Login",
          "X-Verified-Login",
          "Forwarded",
          "X-Forwarded-For"
        }) assertThat(request.getHeader(name)).isNull();
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer local-gateway-to-backend");
    assertThat(request.getHeader("X-Auth-Context")).isNotEqualTo(sid).isNotEqualTo("fake");
  }

  @Test
  void invalidCookieAndPartialPlatformIdentityAreRejected() {
    post("/api/auth/session?channelId=" + CHANNEL)
        .cookie(SessionCookies.NAME, "invalid")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    post("/api/auth/session?channelId=" + CHANNEL)
        .cookie("PlatformAuthentication_O", "demo-ticket")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void mutationRequiresOriginAndInternalRequiresServiceAuthentication() {
    client
        .post()
        .uri("/api/auth/session?channelId=" + CHANNEL)
        .exchange()
        .expectStatus()
        .isForbidden();
    client
        .post()
        .uri("/internal/v1/auth/operations")
        .bodyValue(
            Map.of(
                "operationId",
                "op",
                "version",
                "v1",
                "interfaceId",
                "demo-apply",
                "actionHash",
                Credentials.hash("{}")))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @ParameterizedTest
  @CsvSource({
    "false,/api/chat/threads,",
    "true,/api/chat/threads,",
    "false,/api/chat/threads/thread-one/messages,thread-one",
    "true,/api/chat/threads/thread-one/messages,thread-one",
    "false,/api/chat/threads/thread-one/artifacts/artifact,thread-one",
    "true,/api/chat/threads/thread-one/artifacts/artifact,thread-one"
  })
  void readRoutesPreserveOnlyUsableContextWithoutKeyPromotionOrExtension(
      boolean member, String path, String thread) throws Exception {
    String sid = seed(member, System.currentTimeMillis() + 300000);
    Session before = store.find(Credentials.hash(sid));
    org.mockito.Mockito.clearInvocations(store);
    BACKEND.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
    client
        .get()
        .uri(path)
        .cookie(SessionCookies.NAME, sid)
        .cookie("PlatformVerify_O", "bad")
        .exchange()
        .expectStatus()
        .isOk();
    var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(forwarded).isNotNull();
    if (member && thread != null) {
      var grant =
          store.grant(Credentials.hash(forwarded.getHeader("X-Auth-Context")), clock.millis());
      assertThat(grant.threadId()).isEqualTo(thread);
      assertThat(grant.userType()).isEqualTo("MEMBER");
    } else {
      assertThat(forwarded.getHeader("X-Auth-Context")).isNull();
      org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
          .saveGrant(org.mockito.ArgumentMatchers.any());
    }
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
  }

  @Test
  void guestChatDoesNotDependOnGrantStorageOrForwardSpoofedContext() throws Exception {
    String sid = seed(false, clock.millis() + 300000);
    org.mockito.Mockito.clearInvocations(store);
    org.mockito.Mockito.doThrow(com.example.chatgateway.exception.GatewayException.unavailable())
        .when(store)
        .saveGrant(org.mockito.ArgumentMatchers.any());
    BACKEND.enqueue(
        new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: ok\n\n"));
    post("/api/chat")
        .cookie(SessionCookies.NAME, sid)
        .header("X-Auth-Context", "spoofed")
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk();
    var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(forwarded.getHeader("X-Auth-Context")).isNull();
    assertThat(forwarded.getHeader("X-Authenticated-User-Type")).isEqualTo("GUEST");
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .saveGrant(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void streamContinuesAfterSessionExpiresWhileNewChatIsRejected() throws Exception {
    long started = System.currentTimeMillis();
    when(clock.millis()).thenReturn(started);
    String sid = seed(true, started + 300000);
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: first\n\ndata: second\n\n")
            .throttleBody(13, 1, TimeUnit.SECONDS));
    var stream =
        post("/api/chat?channelId=" + CHANNEL)
            .cookie(SessionCookies.NAME, sid)
            .bodyValue(Map.of("threadId", "thread"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class);
    try (var input = stream.response.body()) {
      assertThat(new String(input.readNBytes(13), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo("data: first\n\n");
      when(clock.millis()).thenReturn(started + 300000);
      post("/api/chat?channelId=" + CHANNEL)
          .cookie(SessionCookies.NAME, sid)
          .bodyValue(Map.of("threadId", "thread"))
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .doesNotExist("Set-Cookie");
      assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo("data: second\n\n");
    }
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    assertThat(BACKEND.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(started + 300000);
  }
}
