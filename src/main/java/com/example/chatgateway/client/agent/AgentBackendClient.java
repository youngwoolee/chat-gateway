package com.example.chatgateway.client.agent;

import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.config.StagingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/** Agent B/E에 HTTP 요청을 보내며, 응답 연결은 소비자가 닫는다. */
@Component
public class AgentBackendClient {
  private final RestClient client;
  private final ChatProperties properties;
  private final StagingProperties staging;
  private final BackendHeaders headers;

  public AgentBackendClient(
      @Qualifier("proxyClient") CloseableHttpClient httpClient,
      ChatProperties properties,
      StagingProperties staging,
      BackendHeaders headers) {
    this.client =
        RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
            .build();
    this.properties = properties;
    this.staging = staging;
    this.headers = headers;
  }

  /** 발화 JSON을 한 번 전송한다. SSE/일반 응답은 호출 서비스가 소비한다. */
  public Response chat(URI target, HttpHeaders identityHeaders, byte[] body) {
    return exchange(
        client.post().uri(target).headers(values -> values.addAll(identityHeaders)).body(body));
  }

  /** 도구 본문은 JSON·form 등 원래 형식 그대로 전송하며 메모리에 모두 적재하지 않는다. */
  public Response executeTool(
      URI target, HttpHeaders identityHeaders, InputStream body, long length) {
    var request = client.post().uri(target).headers(values -> values.addAll(identityHeaders));
    if (length >= 0) request.contentLength(length);
    return exchange(request.body(output -> body.transferTo(output)));
  }

  /** 목록·이력·아티팩트는 본문 없는 GET으로 조회한다. */
  public Response history(URI target, HttpHeaders identityHeaders) {
    return exchange(client.get().uri(target).headers(values -> values.addAll(identityHeaders)));
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

  /** B/E 오류 응답도 그대로 반환한다. SSE 연결은 소비가 끝난 뒤 서비스가 닫는다. */
  private Response exchange(RestClient.RequestHeadersSpec<?> request) {
    return request.exchange(
        (sent, received) -> new Response(received, headers.response(received.getHeaders())), false);
  }

  public record Response(ClientHttpResponse response, HttpHeaders headers)
      implements AutoCloseable {
    public InputStream body() throws IOException {
      return response.getBody();
    }

    public int status() throws IOException {
      return response.getStatusCode().value();
    }

    @Override
    public void close() {
      response.close();
    }
  }
}
