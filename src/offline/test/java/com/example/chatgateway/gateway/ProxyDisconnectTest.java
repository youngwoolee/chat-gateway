package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "chat.backend.connect-timeout-millis=200",
      "chat.backend.max-streams=2",
      "chat.backend.response-timeout=2s",
      "server.tomcat.connection-timeout=700ms"
    })
@ActiveProfiles("offline")
class ProxyDisconnectTest {
  static final ServerSocket BACKEND;

  static {
    try {
      BACKEND = new ServerSocket(0, 1);
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("chat.backend.base-url", () -> "http://127.0.0.1:" + BACKEND.getLocalPort());
  }

  @org.junit.jupiter.api.AfterAll
  static void close() throws Exception {
    BACKEND.close();
  }

  @LocalServerPort int port;
  @Autowired AuthorityStore store;

  @Test
  void peerFinAfterResponseIsDetectedBeforeShortIdleReuse() throws Exception {
    var consumed = new CompletableFuture<Void>();
    var fin = new CompletableFuture<Void>();
    var done = new CompletableFuture<Void>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (Socket first = BACKEND.accept()) {
                    first.setSoTimeout(3000);
                    String request = headers(first);
                    consumeRequestBody(first, request);
                    first
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                                .getBytes(StandardCharsets.US_ASCII));
                    first.getOutputStream().flush();
                    consumed.get(3, TimeUnit.SECONDS);
                    first.shutdownOutput();
                    fin.complete(null);
                    try (Socket second = BACKEND.accept()) {
                      second.setSoTimeout(3000);
                      request = headers(second);
                      consumeRequestBody(second, request);
                      second
                          .getOutputStream()
                          .write(
                              "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                  .getBytes(StandardCharsets.US_ASCII));
                      second.getOutputStream().flush();
                    }
                    try {
                      assertThat(first.getInputStream().read())
                          .as("no request sent to half-closed socket")
                          .isEqualTo(-1);
                    } catch (java.net.SocketException reset) {
                      // HC5 discards stale connections with an immediate close (RST is valid).
                    }
                    done.complete(null);
                  } catch (Throwable error) {
                    fin.completeExceptionally(error);
                    done.completeExceptionally(error);
                  }
                });
    try {
      try (Socket first = connect()) {
        assertThat(headers(first)).startsWith("HTTP/1.1 200");
        assertThat(first.getInputStream().readNBytes(2)).isEqualTo(new byte[] {'o', 'k'});
      }
      consumed.complete(null);
      fin.get(3, TimeUnit.SECONDS);
      Thread.sleep(
          150); // FIN reaches the pool well before HC5's default two-second validation threshold.
      long begin = System.nanoTime();
      try (Socket second = connect()) {
        assertThat(headers(second)).startsWith("HTTP/1.1 200");
        assertThat(second.getInputStream().readNBytes(2)).isEqualTo(new byte[] {'o', 'k'});
      }
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(1000);
      done.get(3, TimeUnit.SECONDS);
    } finally {
      worker.interrupt();
      worker.join(3000);
    }
  }

  @Test
  void downstreamResetAbortsUpstreamWithoutDraining() throws Exception {
    var closed = new CompletableFuture<Boolean>();
    var bytes = new AtomicLong();
    Thread worker = upstream(closed, bytes, false);
    try (Socket downstream = connect()) {
      headers(downstream);
      assertThat(downstream.getInputStream().read()).isNotEqualTo(-1);
      downstream.setSoLinger(true, 0);
    }
    assertThat(closed.get(3, TimeUnit.SECONDS)).isTrue();
    worker.join(2000);
    assertThat(worker.isAlive()).isFalse();
    assertThat(bytes.get()).isLessThan(65536);
  }

  @Test
  void slowReaderWriteTimeoutReleasesUpstreamAndOrdinaryRequestsRemainResponsive()
      throws Exception {
    var closed = new CompletableFuture<Boolean>();
    var bytes = new AtomicLong();
    Thread worker = upstream(closed, bytes, true);
    var otherClosed = new CompletableFuture<Boolean>();
    var otherBytes = new AtomicLong();
    Thread otherWorker = upstream(otherClosed, otherBytes, true);
    try (Socket downstream = connect();
        Socket other = connect()) {
      headers(downstream);
      headers(other);
      new HttpProbe("http://localhost:" + port)
          .get()
          .uri("/api/chat/threads")
          .exchange()
          .expectStatus()
          .isEqualTo(503);
      long begin = System.nanoTime();
      new HttpProbe("http://localhost:" + port)
          .get()
          .uri("/actuator/health")
          .exchange()
          .expectStatus()
          .isOk();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(1000);
      assertThat(closed.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(otherClosed.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(otherBytes.get()).isLessThan(64L * 1024 * 1024);
      System.out.println(
          "Two slow readers: upstream bytes="
              + bytes.get()
              + ","
              + otherBytes.get()
              + "; both upstream sockets closed; third request=503; health=200");
      assertThat(bytes.get()).isLessThan(64L * 1024 * 1024);
    }
    worker.join(2000);
    assertThat(worker.isAlive()).isFalse();
    otherWorker.join(2000);
    assertThat(otherWorker.isAlive()).isFalse();
    var secondClosed = new CompletableFuture<Boolean>();
    Thread second = upstream(secondClosed, new AtomicLong(), false);
    try (Socket recovered = connect()) {
      headers(recovered);
      recovered.setSoLinger(true, 0);
    }
    assertThat(secondClosed.get(3, TimeUnit.SECONDS)).isTrue();
    second.join(2000);
  }

  @Test
  void silentUpstreamReleasesSocketAndAdmissionWithinReadGapAfterClientReset() throws Exception {
    var silentClosed = new CompletableFuture<Boolean>();
    Thread silent =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (Socket socket = BACKEND.accept()) {
                    socket.setSoTimeout(4000);
                    String request = headers(socket);
                    consumeRequestBody(socket, request);
                    socket
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    try {
                      silentClosed.complete(socket.getInputStream().read() == -1);
                    } catch (java.net.SocketException reset) {
                      silentClosed.complete(true);
                    }
                  } catch (Exception error) {
                    silentClosed.completeExceptionally(error);
                  }
                });
    try (Socket lost = connect()) {
      assertThat(headers(lost)).startsWith("HTTP/1.1 200");
      var activeClosed = new CompletableFuture<Boolean>();
      Thread active = upstream(activeClosed, new AtomicLong(), false);
      try (Socket held = connect()) {
        assertThat(headers(held)).startsWith("HTTP/1.1 200");
        new HttpProbe("http://localhost:" + port)
            .get()
            .uri("/api/chat/threads")
            .exchange()
            .expectStatus()
            .isEqualTo(503);
        long begin = System.nanoTime();
        lost.setSoLinger(true, 0);
        lost.close();
        assertThat(silentClosed.get(3, TimeUnit.SECONDS)).isTrue();
        // 소켓 단절 통지와 Gateway finally는 다른 스레드에서 실행되므로 슬롯 반환도 관측한다.
        await()
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(
                () ->
                    new HttpProbe("http://localhost:" + port)
                        .get()
                        .uri("/api/chat/threads")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(401));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(2750);
        var recoveredClosed = new CompletableFuture<Boolean>();
        Thread recovered = upstream(recoveredClosed, new AtomicLong(), false);
        try (Socket next = connect()) {
          assertThat(headers(next)).startsWith("HTTP/1.1 200");
          next.setSoLinger(true, 0);
        }
        assertThat(recoveredClosed.get(3, TimeUnit.SECONDS)).isTrue();
        recovered.join(1000);
        held.setSoLinger(true, 0);
      }
      assertThat(activeClosed.get(3, TimeUnit.SECONDS)).isTrue();
      active.join(1000);
    } finally {
      silent.join(4000);
    }
    assertThat(silent.isAlive()).isFalse();
  }

  @Test
  void saturatedLocalListenBacklogExercisesConnectTimeout() throws Exception {
    var sockets = new java.util.ArrayList<Socket>();
    boolean saturated = false;
    try {
      for (int i = 0; i < 200; i++) {
        var socket = new Socket();
        sockets.add(socket);
        try {
          socket.connect(new java.net.InetSocketAddress("127.0.0.1", BACKEND.getLocalPort()), 30);
        } catch (java.net.SocketTimeoutException expected) {
          saturated = true;
          break;
        }
      }
      assertThat(saturated).as("local listen backlog must saturate for this test").isTrue();
      try (var downstream = connect()) {
        long begin = System.nanoTime();
        assertThat(headers(downstream)).startsWith("HTTP/1.1 503");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(1500);
      }
    } finally {
      for (Socket socket : sockets) socket.close();
      BACKEND.setSoTimeout(100);
      try {
        while (true) BACKEND.accept().close();
      } catch (java.net.SocketTimeoutException drained) {
      } finally {
        BACKEND.setSoTimeout(0);
      }
    }
  }

  /** HTTP framing 방식과 무관하게 요청 본문을 소비한다. Gateway 기본값은 chunked다. */
  private void consumeRequestBody(Socket socket, String headers) throws IOException {
    var input = socket.getInputStream();
    String lower = headers.toLowerCase(Locale.ROOT);
    if (lower.contains("content-length: ")) {
      int length = Integer.parseInt(lower.split("content-length: ")[1].split("\r\n")[0]);
      assertThat(input.readNBytes(length)).hasSize(length);
      return;
    }
    assertThat(lower).contains("transfer-encoding: chunked");
    while (true) {
      var line = new StringBuilder();
      int value;
      while ((value = input.read()) != '\n') {
        if (value == -1) throw new IOException("Incomplete chunk header");
        if (value != '\r') line.append((char) value);
      }
      int length = Integer.parseInt(line.toString().split(";", 2)[0], 16);
      assertThat(input.readNBytes(length)).hasSize(length);
      assertThat(input.readNBytes(2)).containsExactly((byte) '\r', (byte) '\n');
      if (length == 0) return;
    }
  }

  /** raw upstream에서 게이트웨이가 실제 소켓을 닫았는지 읽기 EOF/RST로 관측한다. */
  private Thread upstream(CompletableFuture<Boolean> closed, AtomicLong bytes, boolean flood) {
    return Thread.ofVirtual()
        .start(
            () -> {
              try (Socket socket = BACKEND.accept()) {
                socket.setSoTimeout(15000);
                String request = headers(socket);
                assertThat(request.toLowerCase()).doesNotContain("x-hop:");
                consumeRequestBody(socket, request);
                Thread reader =
                    Thread.ofVirtual()
                        .start(
                            () -> {
                              try {
                                closed.complete(socket.getInputStream().read() == -1);
                              } catch (java.net.SocketTimeoutException timeout) {
                                closed.completeExceptionally(timeout);
                              } catch (IOException reset) {
                                closed.complete(true);
                              }
                            });
                var output = socket.getOutputStream();
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                byte[] body =
                    (flood ? "x".repeat(8192) : "data: beat\n\n").getBytes(StandardCharsets.UTF_8);
                try {
                  while (!closed.isDone()) {
                    output.write(
                        (Integer.toHexString(body.length) + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    bytes.addAndGet(body.length);
                    if (!flood) {
                      try {
                        closed.get(100, TimeUnit.MILLISECONDS);
                      } catch (java.util.concurrent.TimeoutException nextHeartbeat) {
                      }
                    }
                  }
                } catch (IOException writeFailure) {
                  reader.join(1000);
                  if (!closed.isDone()) throw writeFailure;
                }
                reader.join(1000);
              } catch (Exception failure) {
                if (!closed.isDone()) closed.completeExceptionally(failure);
              }
            });
  }

  /** 실제 Tomcat에 작은 수신 버퍼의 원문 요청을 보내 느린 클라이언트를 재현한다. */
  private Socket connect() throws Exception {
    String sid = Credentials.random();
    store.create(
        new Session(
            Credentials.hash(sid),
            "member",
            "MEMBER",
            ProxyTransportTest.CHANNEL,
            "ACTIVE",
            System.currentTimeMillis(),
            System.currentTimeMillis() + 300000,
            null,
            "thread"),
        null);
    Socket socket = new Socket();
    socket.setReceiveBufferSize(1024);
    socket.connect(new java.net.InetSocketAddress("localhost", port));
    socket.setSoTimeout(10000);
    String body = "{\"threadId\":\"thread\"}";
    socket
        .getOutputStream()
        .write(
            ("POST /api/chat HTTP/1.1\r\nHost: localhost:"
                    + port
                    + "\r\nConnection: X-Hop\r\nX-Hop: must-not-forward\r\nOrigin: http://localhost:"
                    + port
                    + "\r\nCookie: "
                    + SessionCookies.NAME
                    + "="
                    + sid
                    + "\r\nIdempotency-Key: "
                    + Credentials.random()
                    + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length()
                    + "\r\n\r\n"
                    + body)
                .getBytes(StandardCharsets.UTF_8));
    socket.getOutputStream().flush();
    return socket;
  }

  /** 본문을 미리 소비하지 않고 HTTP 헤더 끝까지만 읽는다. */
  private static String headers(Socket socket) throws IOException {
    var result = new StringBuilder();
    while (!result.toString().endsWith("\r\n\r\n")) {
      int b = socket.getInputStream().read();
      if (b == -1) throw new IOException("EOF before headers");
      result.append((char) b);
    }
    return result.toString();
  }
}
