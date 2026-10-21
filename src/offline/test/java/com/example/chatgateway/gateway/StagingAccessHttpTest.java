package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("offline")
class StagingAccessHttpTest {
  static final MockWebServer STAGE = new MockWebServer();
  static final MockWebServer PROD = new MockWebServer();

  static {
    try {
      STAGE.start();
      PROD.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("chat.channels.allowed.stage", () -> "PFC");
    r.add("chat.staging.channels.stage", () -> STAGE.url("/").toString());
    r.add("chat.offline.allowed-customers.stage[0]", () -> "local-member");
    r.add("chat.backend.base-url", () -> PROD.url("/").toString());
  }

  @AfterAll
  static void close() throws IOException {
    STAGE.shutdown();
    PROD.shutdown();
  }

  @LocalServerPort int port;
  @Autowired AuthorityStore store;

  String seed(String channel, String customer, boolean member) {
    String sid = Credentials.random();
    long now = System.currentTimeMillis();
    store.create(
        new Session(
            Credentials.hash(sid),
            customer,
            member ? "MEMBER" : "GUEST",
            channel,
            "ACTIVE",
            now,
            now + 60000,
            null,
            "thread"),
        null);
    return sid;
  }

  HttpProbe.Request post(String path) {
    String origin = "http://localhost:" + port;
    return new HttpProbe(origin)
        .post()
        .uri(path)
        .header("Origin", origin)
        .header("Idempotency-Key", UUID.randomUUID().toString());
  }

  @Test
  void guestCannotCreateStagingSession() {
    post("/api/auth/session?channelId=stage").exchange().expectStatus().isEqualTo(403);
  }

  @Test
  void allowedIdentityCanCreateStagingSession() {
    post("/api/auth/session?channelId=stage")
        .cookie("PlatformSystemCode_O", "DEMO")
        .cookie("PlatformAuthentication_O", "demo-ticket")
        .cookie("PlatformVerify_O", "demo-proof")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void rejectedMembersCannotChatClickOrRestart() {
    String sid = seed("stage", "not-allowed", true);
    Session before = store.find(Credentials.hash(sid));
    post("/api/chat")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(403);
    post("/api/click")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "thread", "scenarioId", "s", "state", "s", "component", "c"))
        .exchange()
        .expectStatus()
        .isEqualTo(403);
    post("/api/auth/session/restart")
        .cookie(SessionCookies.NAME, sid)
        .bodyValue(Map.of("threadId", "new-thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(403);
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
  }

  @Test
  void routesByAuthenticatedChannelAndRejectsChannelSwitch() throws Exception {
    String stage = seed("stage", "local-member", true);
    STAGE.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: stage\n\n"));
    post("/api/chat")
        .cookie(SessionCookies.NAME, stage)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(STAGE.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/internal/v1/chat");
    post("/api/chat?channelId=" + SessionHttpIntegrationTest.CHANNEL)
        .cookie(SessionCookies.NAME, stage)
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isEqualTo(403);
    String prod = seed(SessionHttpIntegrationTest.CHANNEL, "guest", false);
    PROD.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
    post("/api/chat")
        .cookie(SessionCookies.NAME, prod)
        .header("X-Channel-Id", "stage")
        .bodyValue(Map.of("threadId", "thread"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(PROD.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/internal/v1/chat");
    assertThat(STAGE.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }
}
