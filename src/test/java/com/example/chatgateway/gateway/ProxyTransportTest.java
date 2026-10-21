package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "chat.backend.max-streams=2",
      "chat.backend.response-timeout=700ms",
      "server.tomcat.connection-timeout=700ms"
    })
@ActiveProfiles("local")
class ProxyTransportTest {
  static final String CHANNEL = "00000000-0000-0000-0000-000000000001";
  static final MockWebServer BACKEND = new MockWebServer();

  static {
    try {
      BACKEND.start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("chat.backend.base-url", () -> BACKEND.url("/").toString());
  }

  @AfterAll
  static void close() throws Exception {
    BACKEND.shutdown();
  }

  @LocalServerPort int port;
  @Autowired AuthorityStore store;
  @Autowired ServletWebServerApplicationContext context;
  HttpProbe client;
  String origin;
  String sid;

  @BeforeEach
  void setup() throws Exception {
    while (BACKEND.takeRequest(1, TimeUnit.MILLISECONDS) != null) {}
    origin = "http://localhost:" + port;
    client = new HttpProbe(origin);
    sid = Credentials.random();
    store.create(
        new Session(
            Credentials.hash(sid),
            "member",
            "MEMBER",
            CHANNEL,
            "ACTIVE",
            System.currentTimeMillis(),
            System.currentTimeMillis() + 300000,
            null,
            "thread"),
        null);
  }

  HttpProbe.Request post(String path) {
    return client
        .post()
        .uri(path)
        .header("Origin", origin)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .cookie(SessionCookies.NAME, sid);
  }

  HttpProbe.Request chat() {
    return client.get().uri("/api/chat/threads/thread/messages").cookie(SessionCookies.NAME, sid);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/api/chat", "/api/chat/threads/thread/tools/tool"})
  void expectContinueIsConsumedWithoutUpstreamStall(String path) throws Exception {
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    byte[] body = "{\"threadId\":\"thread\"}".getBytes(StandardCharsets.UTF_8);
    try (var socket = new java.net.Socket("localhost", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("POST "
                      + path
                      + " HTTP/1.1\r\nHost: localhost:"
                      + port
                      + "\r\nOrigin: "
                      + origin
                      + "\r\nIdempotency-Key: expect\r\nCookie: "
                      + SessionCookies.NAME
                      + "="
                      + sid
                      + "\r\nContent-Type: application/json\r\nContent-Length: "
                      + body.length
                      + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      assertThat(headers(socket)).startsWith("HTTP/1.1 100");
      long begin = System.nanoTime();
      socket.getOutputStream().write(body);
      assertThat(headers(socket)).startsWith("HTTP/1.1 200");
      socket.getInputStream().readAllBytes();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(1000);
    }
    var request = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(request.getHeader("Expect")).isNull();
    assertThat(request.getBody().readByteArray()).isEqualTo(body);
  }

  @Test
  void malformedPercentQueriesReturn400() throws Exception {
    for (String path : new String[] {"/api/chat/threads"}) {
      for (String query : new String[] {"channelId=%zz", "channelId=%", "channel%zz=value"}) {
        try (var socket = new java.net.Socket("localhost", port)) {
          socket.setSoTimeout(3000);
          socket
              .getOutputStream()
              .write(
                  ("GET "
                          + path
                          + "?"
                          + query
                          + " HTTP/1.1\r\nHost: localhost:"
                          + port
                          + "\r\nCookie: "
                          + SessionCookies.NAME
                          + "="
                          + sid
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
          assertThat(headers(socket)).startsWith("HTTP/1.1 400");
          assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
              .contains("INVALID_ARGUMENT")
              .doesNotContain("INTERNAL");
        }
      }
    }
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void optionsAndDirectErrorAre404ButServletErrorDispatchRetainsStatus() throws Exception {
    for (String path :
        new String[] {
          "/api/chat",
          "/api/chat/threads",
          "/api/chat/threads/thread/messages",
          "/api/chat/threads/thread/artifacts/a",
          "/api/chat/threads/thread/tools/tool"
        }) {
      var request =
          HttpRequest.newBuilder(URI.create(origin + path))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build();
      var response = HttpProbe.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body()).contains("NOT_FOUND");
    }
    client
        .get()
        .uri("/error")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.error.status")
        .isEqualTo("NOT_FOUND");
    for (int status : new int[] {400, 500, 503}) {
      client
          .get()
          .uri("/test-error/" + status)
          .exchange()
          .expectStatus()
          .isEqualTo(status)
          .expectBody()
          .jsonPath("$.error.code")
          .isEqualTo(status);
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class ErrorDispatchConfig {
    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
        errorFilter() {
      var registration =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
      registration.setFilter(
          (request, response, chain) -> {
            var path = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            ((jakarta.servlet.http.HttpServletResponse) response)
                .sendError(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)));
          });
      registration.addUrlPatterns("/test-error/*");
      return registration;
    }
  }

  private static String headers(java.net.Socket socket) throws IOException {
    var result = new StringBuilder();
    while (!result.toString().endsWith("\r\n\r\n")) {
      int b = socket.getInputStream().read();
      if (b == -1) throw new IOException("EOF before headers");
      result.append((char) b);
    }
    return result.toString();
  }

  @Test
  void actualTomcatUsesVirtualThreads() throws Exception {
    assertThat(context.getWebServer())
        .isInstanceOf(org.springframework.boot.web.embedded.tomcat.TomcatWebServer.class);
    var tomcat =
        (org.springframework.boot.web.embedded.tomcat.TomcatWebServer) context.getWebServer();
    var executor = tomcat.getTomcat().getConnector().getProtocolHandler().getExecutor();
    var virtual = new java.util.concurrent.CompletableFuture<Boolean>();
    executor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()));
    assertThat(virtual.get(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void rawSseAndFirstChunkArriveBeforeEof() throws Exception {
    byte[] body =
        ": 주석\r\nid: 42\r\nretry: 50\ndata:  two spaces\ndata: 한글\n\nevent: unknown\ndata:\n\n"
            .getBytes(StandardCharsets.UTF_8);
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(new Buffer().write(body), 7)
            .throttleBody(7, 50, TimeUnit.MILLISECONDS));
    var response =
        chat().exchange().expectStatus().isOk().expectHeader().exists("X-Chat-Auth-Expires-At");
    try (var input = response.response.body()) {
      long begin = System.nanoTime();
      assertThat(input.readNBytes(7)).isEqualTo(java.util.Arrays.copyOf(body, 7));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(400);
      assertThat(input.readAllBytes())
          .isEqualTo(java.util.Arrays.copyOfRange(body, 7, body.length));
    }
  }

  @Test
  void responseHeadersArriveBeforeDelayedBody() throws Exception {
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: later\n\n")
            .setBodyDelay(400, TimeUnit.MILLISECONDS));
    long begin = System.nanoTime();
    var response = chat().exchange().expectStatus().isOk();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(350);
    assertThat(response.getResponseBody())
        .isEqualTo("data: later\n\n".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void backendNoContentAndBusinessConflictPassThroughUnchanged() throws Exception {
    for (int code : new int[] {204, 409}) {
      String body = code == 204 ? "" : "{\"businessCode\":\"LIMIT_CHANGED\"}";
      BACKEND.enqueue(
          new MockResponse()
              .setResponseCode(code)
              .setHeader("Content-Type", "application/json")
              .setBody(body));
      var response = chat().exchange().expectStatus().isEqualTo(code);
      assertThat(response.getResponseBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
      assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }
  }

  @Test
  void binaryStatusDuplicateHeadersCookiesAndRawQueryPassUnchanged() throws Exception {
    byte[] binary = {0, (byte) 255, 1, 13, 10};
    BACKEND.enqueue(
        new MockResponse()
            .setResponseCode(502)
            .setHeader("Content-Type", "application/octet-stream")
            .addHeader("X-Multi", "one")
            .addHeader("X-Multi", "two")
            .addHeader("Set-Cookie", "a=one; Path=/")
            .addHeader("Set-Cookie", "b=two; Path=/")
            .addHeader("Connection", "X-Hop")
            .addHeader("X-Hop", "private")
            .setBody(new Buffer().write(binary)));
    var response =
        client
            .get()
            .uri("/api/chat/threads/thread/artifacts/a?cursor=a%20b&x=1&x=2")
            .cookie(SessionCookies.NAME, sid)
            .exchange()
            .expectStatus()
            .isEqualTo(502);
    assertThat(response.getResponseBody()).isEqualTo(binary);
    assertThat(response.response.headers().allValues("X-Multi")).containsExactly("one", "two");
    assertThat(response.response.headers().allValues("Set-Cookie")).hasSize(2);
    assertThat(response.response.headers().allValues("X-Hop")).isEmpty();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getPath())
        .isEqualTo("/internal/v1/chat/threads/thread/artifacts/a?cursor=a%20b&x=1&x=2");
  }

  @Test
  void formToolBodyAndEncodedQueryRemainUnchanged() throws Exception {
    String query = "flag&empty=&x=a+b&x=a%20b&encoded=%2F%26%3D";
    String body = "message=a+b&message=a%20b&opaque=%2F%26%3D";
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat/threads/thread/tools/tool?" + query)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    var upstream = BACKEND.takeRequest(2, TimeUnit.SECONDS);
    assertThat(upstream.getPath())
        .isEqualTo("/internal/v1/chat/threads/thread/tools/tool?" + query);
    assertThat(upstream.getBody().readUtf8()).isEqualTo(body);
  }

  @Test
  void gatewayHandlesConditionalGetAndReleasesResponse() throws Exception {
    BACKEND.enqueue(new MockResponse().setHeader("ETag", "\"v1\"").setBody("fresh"));
    var response =
        client
            .get()
            .uri("/api/chat/threads/thread/artifacts/a")
            .cookie(SessionCookies.NAME, sid)
            .header("If-None-Match", "\"v1\"")
            .exchange()
            .expectStatus()
            .isEqualTo(304);
    assertThat(response.getResponseBody()).isEmpty();
    assertThat(response.response.headers().firstValue("ETag")).contains("\"v1\"");
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getHeader("If-None-Match"))
        .isEqualTo("\"v1\"");
    BACKEND.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""));
    client
        .get()
        .uri("/api/chat/threads/thread/artifacts/a")
        .cookie(SessionCookies.NAME, sid)
        .header("If-None-Match", "\"v1\"")
        .exchange()
        .expectStatus()
        .isEqualTo(304)
        .getResponseBody();
  }

  @Test
  void chatByteLimitIncludesChunkedBodiesAndToolsHaveNoNewLimit() throws Exception {
    String prefix = "{\"threadId\":\"thread\",\"text\":\"";
    String exact = prefix + "a".repeat(1048576 - prefix.length() - 2) + "\"}";
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat").bodyValue(exact).exchange().expectStatus().isOk().getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getBodySize()).isEqualTo(1048576);
    post("/api/chat").bodyValue(exact + " ").exchange().expectStatus().isEqualTo(400);
    var request =
        HttpRequest.newBuilder(URI.create(origin + "/api/chat"))
            .header("Origin", origin)
            .header("Cookie", SessionCookies.NAME + "=" + sid)
            .header("Idempotency-Key", "chunked")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () ->
                        new java.io.ByteArrayInputStream(
                            (exact + " ").getBytes(StandardCharsets.UTF_8))))
            .build();
    assertThat(HttpProbe.HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(400);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    BACKEND.enqueue(new MockResponse().setBody("ok"));
    post("/api/chat/threads/thread/tools/tool")
        .bodyValue(exact + " ")
        .exchange()
        .expectStatus()
        .isOk()
        .getResponseBody();
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS).getBodySize()).isEqualTo(1048577);
  }

  @Test
  void delayedHeadersReturn503AndNoRetry() throws Exception {
    int before = BACKEND.getRequestCount();
    BACKEND.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("late"));
    chat()
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error.status")
        .isEqualTo("UNAVAILABLE");
    assertThat(BACKEND.getRequestCount() - before).isEqualTo(1);
  }

  @Test
  void readGapAndPrematureCloseAreAbnormalWithoutErrorJson() throws Exception {
    int before = BACKEND.getRequestCount();
    for (boolean timeout : new boolean[] {true, false}) {
      var upstream =
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setChunkedBody("data: first\n\ndata: second\n\n", 13);
      if (timeout) upstream.throttleBody(13, 2, TimeUnit.SECONDS);
      else upstream.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
      BACKEND.enqueue(upstream);
      var response = chat().exchange().expectStatus().isOk();
      var received = new java.io.ByteArrayOutputStream();
      try (var input = response.response.body()) {
        assertThatThrownBy(
                () -> {
                  int b;
                  while ((b = input.read()) != -1) received.write(b);
                })
            .isInstanceOf(IOException.class);
      }
      assertThat(received.toString(StandardCharsets.UTF_8))
          .doesNotContain("error", "BAD_GATEWAY", "DEADLINE_EXCEEDED");
    }
    assertThat(BACKEND.getRequestCount() - before).as("오류 디스패치가 B/E를 재호출하면 안 됨").isEqualTo(2);
  }

  @Test
  void fullAdmissionRejectsImmediatelyAndRecoversIndependentlyWithinSameSession() throws Exception {
    String body = "data: beat\n\n".repeat(20);
    BACKEND.enqueue(
        new MockResponse().setChunkedBody(body, 12).throttleBody(12, 100, TimeUnit.MILLISECONDS));
    BACKEND.enqueue(
        new MockResponse().setChunkedBody(body, 12).throttleBody(12, 100, TimeUnit.MILLISECONDS));
    var first = chat().exchange().expectStatus().isOk();
    var second = chat().exchange().expectStatus().isOk();
    long begin = System.nanoTime();
    chat().exchange().expectStatus().isEqualTo(503);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(400);
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    first.response.body().close();
    assertThat(second.getResponseBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    BACKEND.enqueue(new MockResponse().setBody("recovered"));
    chat().exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("recovered");
  }

  @Test
  void proxyDoesNotRetryOrFollowRedirectAndPreservesCompressedBytes() throws Exception {
    BACKEND.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    chat().exchange().expectStatus().isEqualTo(503);
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    BACKEND.enqueue(
        new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", "/must-not-follow")
            .setBody("redirect"));
    chat().exchange().expectStatus().isEqualTo(302).expectBody(String.class).isEqualTo("redirect");
    assertThat(BACKEND.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
    var zipped = new java.io.ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(zipped)) {
      gzip.write("data: 한글\n\n".getBytes(StandardCharsets.UTF_8));
    }
    BACKEND.enqueue(
        new MockResponse()
            .setHeader("Content-Encoding", "gzip")
            .setBody(new Buffer().write(zipped.toByteArray())));
    assertThat(chat().exchange().expectStatus().isOk().getResponseBody())
        .isEqualTo(zipped.toByteArray());
  }

  @Test
  void invalidChatBodiesNeverReachBackend() throws Exception {
    for (String body : new String[] {"", "{", "[]", "{}", "{\"threadId\":1}"})
      post("/api/chat").bodyValue(body).exchange().expectStatus().isEqualTo(400);
    assertThat(BACKEND.takeRequest(50, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void routingErrorsAndDuplicateCookiesKeepWireContract() {
    for (String path : new String[] {"/api/chat", "/api/chat/threads/thread/tools/tool"})
      client
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isNotFound()
          .expectBody()
          .jsonPath("$.error.message")
          .isEqualTo("");
    post("/api/chat/threads").exchange().expectStatus().isNotFound();
    post("/api/auth/session/reset").exchange().expectStatus().isNotFound();
    client
        .get()
        .uri("/missing")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .doesNotExist("Set-Cookie");
    post("/api/auth/session")
        .cookie(SessionCookies.NAME, sid)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    post("/api/click")
        .bodyValue("{")
        .exchange()
        .expectStatus()
        .isEqualTo(400)
        .expectBody()
        .jsonPath("$.error.status")
        .isEqualTo("INVALID_ARGUMENT");
    post("/api/click")
        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isEqualTo(400)
        .expectBody()
        .jsonPath("$.error.message")
        .isEqualTo("");
  }
}
