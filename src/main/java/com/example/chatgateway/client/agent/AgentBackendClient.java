package com.example.chatgateway.client.agent;

import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.config.StagingProperties;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Agent B/E에 HTTP 요청을 보내며, 구독 취소 시 응답 연결도 정리한다. */
@Component
public class AgentBackendClient {
  private final WebClient client;
  private final ChatProperties properties;
  private final StagingProperties staging;
  private final BackendHeaders headers;

  public AgentBackendClient(
      @Qualifier("proxyClient") WebClient client,
      ChatProperties properties,
      StagingProperties staging,
      BackendHeaders headers) {
    this.client = client;
    this.properties = properties;
    this.staging = staging;
    this.headers = headers;
  }

  /** 발화 JSON을 한 번 전송한다. SSE/일반 응답은 호출 서비스가 소비한다. */
  public WebClient.RequestHeadersSpec<?> chat(
      URI target, HttpHeaders identityHeaders, byte[] body) {
    return client
        .post()
        .uri(target)
        .headers(values -> values.addAll(identityHeaders))
        .bodyValue(body);
  }

  /** 도구 본문은 JSON·form 등 원래 형식 그대로 전송하며 메모리에 모두 적재하지 않는다. */
  public WebClient.RequestHeadersSpec<?> executeTool(
      URI target, HttpHeaders identityHeaders, InputStream body, long length) {
    var request = client.post().uri(target).headers(values -> values.addAll(identityHeaders));
    if (length >= 0) request.contentLength(length);
    return request.body(
        DataBufferUtils.readInputStream(() -> body, DefaultDataBufferFactory.sharedInstance, 8192)
            .subscribeOn(Schedulers.boundedElastic()),
        DataBuffer.class);
  }

  /** 목록·이력·아티팩트는 본문 없는 GET으로 조회한다. */
  public WebClient.RequestHeadersSpec<?> history(URI target, HttpHeaders identityHeaders) {
    return client.get().uri(target).headers(values -> values.addAll(identityHeaders));
  }

  /** 채널은 인증된 세션에서만 받는다. 경로와 query를 다시 인코딩하지 않는다. */
  public URI target(String channel, String path, String query) {
    String base = staging.channels().getOrDefault(channel, properties.backend().baseUrl());
    String upstreamQuery =
        query == null
            ? ""
            : Arrays.stream(query.split("&", -1))
                .filter(
                    part ->
                        !"channelId"
                            .equals(UriUtils.decode(part.split("=", 2)[0], StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    return URI.create(
        base.replaceAll("/+$", "")
            + "/api/chat"
            + path
            + (upstreamQuery.isEmpty() ? "" : "?" + upstreamQuery));
  }

  /** 오류 상태도 소비자에게 전달하며, 소비·취소가 끝나면 WebClient가 응답을 정리한다. */
  public Flux<DataBuffer> exchange(
      WebClient.RequestHeadersSpec<?> request, Function<Response, Flux<DataBuffer>> consume) {
    return request.exchangeToFlux(
        received ->
            consume.apply(
                new Response(
                    received.statusCode().value(),
                    headers.response(received.headers().asHttpHeaders()),
                    received.bodyToFlux(DataBuffer.class))));
  }

  public record Response(int status, HttpHeaders headers, Flux<DataBuffer> body) {}
}
