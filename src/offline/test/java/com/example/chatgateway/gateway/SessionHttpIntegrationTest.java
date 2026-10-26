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
    client = new HttpProbe(origin, CHANNEL);
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
            member ? "P000000001" : "guest-demo",
            member ? "MEMBER" : "GUEST",
            CHANNEL,
            "ACTIVE",
            System.currentTimeMillis(),
            expires,
            null),
        null);
    return sid;
  }

  HttpProbe.Request post(String path) {
    return client.post().uri(path).header("Origin", origin);
  }

  @Test
  void channelSelectionNeverFallsBackToAnotherChannelCookie() throws Exception {
    String sid = seed(false, System.currentTimeMillis() + 60000);
    var before = store.find(Credentials.hash(sid));
    org.mockito.Mockito.clearInvocations(platform);
    for (String method : new String[] {"GET", "POST"}) {
      var request =
          method.equals("GET")
              ? client.get().uri("/api/chat/threads?channelId=other")
              : post("/api/chat?channelId=other")
                  .cookie(SessionCookies.name(CHANNEL), sid)
                  .bodyValue(Map.of("threadId", "thread"));
      if (method.equals("GET")) request.cookie(SessionCookies.name(CHANNEL), sid);
      request
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .doesNotExist("Set-Cookie")
          .expectBody()
          .jsonPath("$.error.status")
          .isEqualTo("UNAUTHENTICATED");
    }
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    org.mockito.Mockito.verifyNoInteractions(platform);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void requestsNeedNoIdempotencyKeyAndSuppliedKeysAreIgnored() throws Exception {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String sid = seed(false, now + 60000);
    org.mockito.Mockito.clearInvocations(platform);
    for (String path :
        new String[] {
          "/api/auth/session", "/api/chat", "/api/chat/threads/thread/tools/tool/data", "/api/click"
        }) {
      for (boolean supplied : new boolean[] {false, true}) {
        boolean relay = path.startsWith("/api/chat");
        if (relay) BACKEND.enqueue(new MockResponse().setBody("ok"));
        var request =
            post(path)
                .cookie(SessionCookies.name(CHANNEL), sid)
                .bodyValue(
                    Map.of(
                        "threadId",
                        "thread",
                        "state",
                        "selected",
                        "scenarioId",
                        "limit",
                        "component",
                        "next"));
        if (supplied)
          request.header("Idempotency-Key", "invalid key").header("Idempotency-Key", "another key");
        request.exchange().expectStatus().isOk();
        if (relay) {
          var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
          assertThat(forwarded).isNotNull();
          assertThat(forwarded.getHeader("Idempotency-Key")).isNull();
        }
      }
    }
    org.mockito.Mockito.verify(store, org.mockito.Mockito.times(8))
        .touch(
            org.mockito.ArgumentMatchers.eq(Credentials.hash(sid)),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull());
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
            .cookie(SessionCookies.name(CHANNEL), sid)
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
        .cookie(SessionCookies.name(CHANNEL), sid)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(now + 301000);
    post("/api/auth/session")
        .cookie(SessionCookies.name(CHANNEL), seed(false, now + 60000))
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
            org.mockito.ArgumentMatchers.isNull());
    post("/api/auth/session")
        .cookie(SessionCookies.name(CHANNEL), sid)
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .doesNotExist("Set-Cookie");
  }

  @Test
  void legacyHomepageAndActivityEndpointsAreNotExposed() {
    post("/api/auth/homepage-transfer").exchange().expectStatus().isNotFound();
    client.get().uri("/api/auth/session").exchange().expectStatus().isEqualTo(405);
    post("/api/auth/activity").exchange().expectStatus().isNotFound();
    org.mockito.Mockito.verifyNoInteractions(platform);
  }

  @Test
  void homepageIssuanceFailurePreventsChatForwarding() throws Exception {
    org.mockito.Mockito.doThrow(com.example.chatgateway.exception.GatewayException.unavailable())
        .when(platform)
        .createLoginCookie(org.mockito.ArgumentMatchers.anyString());
    post("/api/chat")
        .cookie(SessionCookies.name(CHANNEL), seed(true, System.currentTimeMillis() + 60000))
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .doesNotExist("Set-Cookie");
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void clickExtendsEachActivityWithoutPfcAndCannotReviveExpiredSession() throws Exception {
    long now = System.currentTimeMillis();
    when(clock.millis()).thenReturn(now);
    String sid = seed(true, now + 10000);
    org.mockito.Mockito.clearInvocations(platform);
    for (int i = 0; i < 2; i++) {
      when(clock.millis()).thenReturn(now + i * 1000);
      post("/api/click")
          .cookie(SessionCookies.name(CHANNEL), sid)
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
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(now + 301000);
    org.mockito.Mockito.verifyNoInteractions(platform);
    when(clock.millis()).thenReturn(now + 301000);
    post("/api/click")
        .cookie(SessionCookies.name(CHANNEL), sid)
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
  void clickRejectsInvalidInputBeforeBackendOrExtension() throws Exception {
    String sid = seed(true, clock.millis() + 60000);
    var before = store.find(Credentials.hash(sid));
    for (String body :
        new String[] {
          "{\"threadId\":\"thread\"}",
          "{\"threadId\":\"thread\",\"scenarioId\":\"s\",\"state\":\"\",\"component\":{}}"
        }) {
      post("/api/click")
          .cookie(SessionCookies.name(CHANNEL), sid)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .isEqualTo(400);
    }
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
          .cookie(SessionCookies.name(CHANNEL), sid)
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
    String scenario = "collector-" + UUID.randomUUID();
    post("/api/click")
        .cookie(SessionCookies.name(CHANNEL), sid)
        .bodyValue(
            Map.of(
                "threadId",
                "thread",
                "scenarioId",
                scenario,
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
                      .filter(value -> value.contains(scenario))
                      .findFirst()
                      .orElseThrow();
              var envelope = mapper.readTree(line);
              assertThat(envelope.path("logger_name").asText()).isEqualTo("AUDIT");
              var message = envelope.path("message");
              assertThat(message.has("key")).isFalse();
              assertThat(message.path("name").asText()).isEqualTo("click");
              assertThat(message.path("chatbot").asText()).isEqualTo("new-chatbot");
              assertThat(message.path("userId").asText()).isEqualTo("P000000001");
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
          .cookie(SessionCookies.name(CHANNEL), sid)
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
  void legacyAuthenticationEndpointsAreNotExposed() {
    for (String path :
        new String[] {
          "/api/auth/session/refresh",
          "/api/auth/session/recover",
          "/api/auth/session/restart",
          "/api/auth/mfa"
        }) {
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
  void createsHttpOnlySessionAndRejectsSessionGet() {
    var response =
        post("/api/auth/session?channelId=" + CHANNEL)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.userType")
            .isEqualTo("GUEST")
            .returnResult();
    var cookie = response.getResponseCookies().getFirst(SessionCookies.name(CHANNEL));
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
        .cookie(SessionCookies.name(CHANNEL), cookie.getValue())
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
          .cookie(SessionCookies.name(CHANNEL), sid)
          .cookie("PlatformSystemCode_O", "DEMO")
          .cookie("PlatformAuthentication_O", "demo-ticket")
          .cookie("PlatformVerify_O", "demo-proof")
          .bodyValue(Map.of("threadId", "thread"))
          .exchange()
          .expectStatus()
          .isUnauthorized()
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
        .cookie(SessionCookies.name(CHANNEL), sid)
        .cookie("PlatformVerify_O", "malformed")
        .header("X-Authenticated-User-Id", "attacker")
        .header("X-Authenticated-User-Type", "MEMBER")
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
    assertThat(request.getPath()).isEqualTo("/api/chat");
    assertThat(request.getBody().readUtf8()).isEqualTo(body);
    assertThat(request.getHeader("X-Authenticated-User-Id")).isEqualTo("P000000001");
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
    assertThat(request.getHeader("Authorization")).isNull();
    assertThat(request.getHeader("X-Authenticated-User-Type")).isNull();
    assertThat(request.getHeader("X-Auth-Context")).isNotEqualTo(sid).isNotEqualTo("fake");
  }

  @Test
  void invalidSessionIsRejectedAndPartialIdentityCreatesGuest() {
    post("/api/auth/session?channelId=" + CHANNEL)
        .cookie(SessionCookies.name(CHANNEL), "invalid")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    post("/api/auth/session?channelId=" + CHANNEL)
        .cookie("PlatformAuthentication_O", "demo-ticket")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void mutationRequiresOriginAndRemovedRegistrationIsNotExposed() {
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
        .isNotFound();
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
            .cookie(SessionCookies.name(CHANNEL), sid)
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
          .cookie(SessionCookies.name(CHANNEL), sid)
          .bodyValue(Map.of("threadId", "thread"))
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .exists("Set-Cookie");
      assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo("data: second\n\n");
    }
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    assertThat(BACKEND.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(started + 300000);
  }

  @Test
  void oneSessionCanChatAndQueryWizardAcrossThreadsWithRawAgUi() throws Exception {
    var created = post("/api/auth/session").exchange().expectStatus().isOk();
    assertThat(mapper.readTree(created.getResponseBody()).has("threadId")).isFalse();
    String sid = created.getResponseCookies().getFirst(SessionCookies.name(CHANNEL)).getValue();
    for (String thread : new String[] {"first-thread", "second-thread"}) {
      String events =
          "data: {\"type\":\"RUN_STARTED\",\"runId\":\"3\"}\n\ndata: {\"type\":\"RUN_FINISHED\"}\n\n";
      BACKEND.enqueue(
          new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events));
      post("/api/chat")
          .cookie(SessionCookies.name(CHANNEL), sid)
          .header("X-Verified-Request-Id", "spoofed")
          .header("X-Verified-Auth-Requests", "spoofed")
          .header("X-Authenticated-Guest-Id", "spoofed")
          .header("X-Request-Id", "spoofed")
          .bodyValue(Map.of("threadId", thread, "resume", java.util.List.of()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .exists("X-Chat-User-Type")
          .expectBody(String.class)
          .isEqualTo(events);
      var chat = BACKEND.takeRequest(2, TimeUnit.SECONDS);
      assertThat(chat.getPath()).isEqualTo("/api/chat");
      assertThat(chat.getHeader("X-Authenticated-User-Id"))
          .isEqualTo(store.find(Credentials.hash(sid)).subject());
      assertThat(chat.getHeader("X-Authenticated-Channel-Id")).isEqualTo(CHANNEL);
      assertThat(chat.getHeader("X-Authenticated-Guest-Id")).isNull();
      assertThat(chat.getHeader("X-Request-Id")).isNull();
      assertThat(chat.getHeader("X-Verified-Request-Id")).isNull();
      assertThat(chat.getHeader("X-Verified-Auth-Requests")).isNull();
      assertThat(mapper.readTree(chat.getBody().readUtf8()).path("threadId").asText())
          .isEqualTo(thread);
      BACKEND.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"cards\":[]}"));
      String path = "/api/chat/threads/" + thread + "/tools/process_lost_card_report/cards";
      post(path)
          .cookie(SessionCookies.name(CHANNEL), sid)
          .bodyValue(Map.of())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .isEqualTo("{\"cards\":[]}");
      assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo(path);
      post("/api/click")
          .cookie(SessionCookies.name(CHANNEL), sid)
          .bodyValue(
              Map.of("threadId", thread, "scenarioId", "s", "state", "step", "component", "button"))
          .exchange()
          .expectStatus()
          .isOk();
    }
  }

  @Test
  void ticketMfaForwardsVerifiedInterruptOnlyAfterPfcSuccess() throws Exception {
    String sid = seed(true, clock.millis() + 60000);
    String header =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mapper.writeValueAsBytes(
                    Map.of(
                        "identifier",
                        Map.of("ssn", "demo-ssn", "iPin", "demo-ipin"),
                        "ticketIds",
                        java.util.List.of("demo-mfa-ticket"),
                        "code",
                        "demo-code")));
    var body =
        Map.of(
            "threadId",
            "new-thread",
            "resume",
            java.util.List.of(Map.of("interruptId", "int-3", "status", "resolved")));
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: done\n\n"));
    post("/api/chat")
        .cookie(SessionCookies.name(CHANNEL), sid)
        .cookie("__state_as_id", "demo-state")
        .header("X-Mfa-Result", header)
        .header("X-Verified-Request-Id", "spoofed")
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    var forwarded = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(forwarded.getHeader("X-Verified-Request-Id")).isEqualTo("int-3");
    assertThat(forwarded.getHeader("X-Mfa-Result")).isNull();
    assertThat(forwarded.getHeader("Cookie")).isNull();
    post("/api/chat")
        .cookie(SessionCookies.name(CHANNEL), sid)
        .cookie("__state_as_id", "bad-state")
        .header("X-Mfa-Result", header)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .exists("X-Chat-User-Type")
        .expectBody()
        .jsonPath("$.error.status")
        .isEqualTo("MFA_INVALID");
    post("/api/chat")
        .cookie(SessionCookies.name(CHANNEL), sid)
        .header("X-Mfa-Result", header)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(400);
    assertThat(BACKEND.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void expiredStartCreatesGuestWhileChatStillRequiresSession() {
    String sid = seed(false, clock.millis() - 1);
    var restarted =
        post("/api/auth/session")
            .cookie(SessionCookies.name(CHANNEL), sid)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .exists("X-Server-Time")
            .exists("X-Chat-User-Type")
            .exists("X-Chat-Auth-Expires-At");
    assertThat(restarted.getResponseCookies().getFirst(SessionCookies.name(CHANNEL)).getValue())
        .isNotBlank().isNotEqualTo(sid);
    post("/api/chat")
        .bodyValue(Map.of("threadId", "any"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .exists("X-Server-Time")
        .doesNotExist("X-Chat-User-Type");
  }

  @Test
  void backendBusinessErrorsPassThroughWithAuthoritativeHeaders() throws Exception {
    String sid = seed(false, clock.millis() + 60000);
    for (int status : new int[] {403, 404, 422, 500}) {
      String error =
          "{\"error\":{\"code\":" + status + ",\"status\":\"BACKEND_ERROR\",\"message\":\"\"}}";
      BACKEND.enqueue(
          new MockResponse()
              .setResponseCode(status)
              .setHeader("Content-Type", "application/json")
              .setHeader("X-Chat-User-Type", "spoofed")
              .setHeader("X-Server-Time", "0")
              .setBody(error));
      var reply =
          post("/api/chat")
              .cookie(SessionCookies.name(CHANNEL), sid)
              .bodyValue(Map.of("threadId", "any"))
              .exchange()
              .expectStatus()
              .isEqualTo(status)
              .expectBody(String.class)
              .isEqualTo(error);
      assertThat(reply.response.headers().allValues("X-Chat-User-Type")).containsExactly("GUEST");
      assertThat(reply.response.headers().allValues("X-Server-Time"))
          .hasSize(1)
          .doesNotContain("0");
      BACKEND.takeRequest(2, TimeUnit.SECONDS);
    }
  }
}
