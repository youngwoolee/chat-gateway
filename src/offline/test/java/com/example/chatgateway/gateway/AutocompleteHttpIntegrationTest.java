package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.cloud.loadbalancer.cache.enabled=false")
@ActiveProfiles("offline")
class AutocompleteHttpIntegrationTest {
  static final MockWebServer SEARCH = new MockWebServer();

  static {
    try {
      SEARCH.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.cloud.discovery.client.simple.instances.search-autocomplete-service[0].uri",
        () -> SEARCH.url("/").toString());
  }

  @AfterAll
  static void close() throws IOException {
    SEARCH.shutdown();
  }

  @LocalServerPort int port;
  @Autowired AuthorityStore store;
  @Autowired ObjectMapper mapper;

  String seed(boolean member, boolean expired) {
    String sid = Credentials.random();
    long now = System.currentTimeMillis();
    store.create(
        new Session(
            Credentials.hash(sid),
            "test-user",
            member ? "MEMBER" : "GUEST",
            SessionHttpIntegrationTest.CHANNEL,
            "ACTIVE",
            now - 1000,
            expired ? now - 1 : now + 60000,
            null,
            "thread"),
        null);
    return sid;
  }

  HttpProbe.Request request(String sid) {
    String origin = "http://localhost:" + port;
    return new HttpProbe(origin)
        .post()
        .uri("/api/chat/auto")
        .header("Origin", origin)
        .cookie(SessionCookies.NAME, sid);
  }

  @Test
  void discoversSearchAndPreservesLegacyContractWithoutTouchOrCredentials() throws Exception {
    String upstream =
        "{\"totalCount\":1,\"chatResponse\":[{\"highlight\":\"<b>카드</b>\",\"score\":1.5,\"auto_result\":\"카드 신청\",\"weight\":2,\"boost\":\"1\",\"nori_result\":\"카드\",\"ngram_result\":\"카드\"}]}";
    for (boolean member : new boolean[] {false, true}) {
      String sid = seed(member, false);
      Session before = store.find(Credentials.hash(sid));
      SEARCH.enqueue(
          new MockResponse().setHeader("Content-Type", "application/json").setBody(upstream));
      var response =
          request(sid)
              .header("Authorization", "Bearer must-not-forward")
              .cookie("PlatformVerify_O", "ignored")
              .bodyValue(Map.of("queryText", "카드"))
              .exchange()
              .expectStatus()
              .isOk()
              .expectHeader()
              .doesNotExist("Set-Cookie");
      var body = mapper.readTree(response.getResponseBody());
      assertThat(body.path("code").asText()).isEqualTo("SUCCESS");
      assertThat(body.path("message").asText()).isEqualTo("성공");
      assertThat(body.path("data")).isEqualTo(mapper.readTree(upstream));
      var called = SEARCH.takeRequest(1, TimeUnit.SECONDS);
      assertThat(called.getMethod()).isEqualTo("POST");
      assertThat(called.getPath()).isEqualTo("/api/autocomplete/chat");
      assertThat(mapper.readTree(called.getBody().readUtf8()))
          .isEqualTo(
              mapper.readTree("{\"queryText\":\"카드\",\"size\":10,\"channel\":\"sol-chat\"}"));
      assertThat(called.getHeader("Cookie")).isNull();
      assertThat(called.getHeader("Authorization")).isNull();
      assertThat(store.find(before.idHash())).isEqualTo(before);
    }
  }

  @Test
  void validatesInputAndSessionBeforeCallingSearch() throws Exception {
    String sid = seed(false, false);
    for (String body :
        new String[] {
          "{}",
          "{\"queryText\":\" \"}",
          "{\"queryText\":\"a\",\"size\":0}",
          "{\"queryText\":\"a\",\"size\":1001}"
        }) request(sid).bodyValue(body).exchange().expectStatus().isEqualTo(400);
    request(seed(true, true))
        .bodyValue(Map.of("queryText", "a"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    request(Credentials.random())
        .bodyValue(Map.of("queryText", "a"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertThat(SEARCH.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void emptyResultsAndCustomQueryArePreservedButBrokenResponsesFail() throws Exception {
    String sid = seed(false, false);
    SEARCH.enqueue(new MockResponse().setBody("{\"totalCount\":0,\"chatResponse\":[]}"));
    request(sid)
        .bodyValue(Map.of("queryText", "a", "size", 3, "channel", "sol-chat"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.totalCount")
        .isEqualTo(0);
    assertThat(mapper.readTree(SEARCH.takeRequest().getBody().readUtf8()).path("size").asInt())
        .isEqualTo(3);
    for (String body : new String[] {"{}", "null", "not-json"}) {
      SEARCH.enqueue(new MockResponse().setBody(body));
      request(sid).bodyValue(Map.of("queryText", "a")).exchange().expectStatus().isEqualTo(503);
      assertThat(SEARCH.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
    }
    SEARCH.enqueue(new MockResponse().setResponseCode(500));
    request(sid).bodyValue(Map.of("queryText", "a")).exchange().expectStatus().isEqualTo(503);
    assertThat(SEARCH.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
  }
}
