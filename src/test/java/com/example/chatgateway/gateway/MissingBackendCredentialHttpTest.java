package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "chat.session.backend-credential= ")
@ActiveProfiles("local")
class MissingBackendCredentialHttpTest {
  @LocalServerPort int port;
  @Autowired AuthorityStore store;
  @MockitoBean PfcClient platform;

  @Test
  void unavailableProxyDoesNotTouchGuestOrMember() {
    String origin = "http://localhost:" + port;
    var client = new HttpProbe(origin);
    for (boolean member : new boolean[] {false, true}) {
      String sid = Credentials.random();
      var before =
          new Session(
              Credentials.hash(sid),
              "subject",
              member ? "MEMBER" : "GUEST",
              SessionHttpIntegrationTest.CHANNEL,
              "ACTIVE",
              1,
              System.currentTimeMillis() + 60000,
              null,
              "thread");
      store.create(before, null);
      client
          .post()
          .uri("/api/chat")
          .header("Origin", origin)
          .header("Idempotency-Key", "key")
          .cookie(SessionCookies.NAME, sid)
          .cookie("PlatformSystemCode_O", "DEMO")
          .cookie("PlatformAuthentication_O", "demo-ticket")
          .cookie("PlatformVerify_O", "demo-proof")
          .bodyValue(Map.of("threadId", "thread"))
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .doesNotExist("Set-Cookie");
      assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    }
    verifyNoInteractions(platform);
    client
        .post()
        .uri("/api/auth/session?channelId=" + SessionHttpIntegrationTest.CHANNEL)
        .header("Origin", origin)
        .header("Idempotency-Key", Credentials.random())
        .exchange()
        .expectStatus()
        .isOk();
  }
}
