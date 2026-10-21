package com.example.chatgateway.client.search;

import com.example.chatgateway.exception.GatewayException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AutocompleteClient {
  private final RestClient client;
  private final ObjectMapper mapper;

  /** 자동완성 전용 클라이언트와 응답 JSON 파서를 주입한다. */
  public AutocompleteClient(
      @Qualifier("autocompleteRestClient") RestClient client, ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  /** 이전 서비스의 요청 필드와 응답 데이터를 유지하며 잘못된 응답은503으로 종료한다. */
  public JsonNode search(Query query) {
    try {
      return client
          .post()
          .uri("/api/autocomplete/chat")
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(query)
          .exchange(
              (request, response) -> {
                if (response.getStatusCode().value() != 200) throw GatewayException.unavailable();
                byte[] bytes = response.getBody().readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) throw GatewayException.unavailable();
                JsonNode data =
                    mapper
                        .reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(bytes);
                if (data == null
                    || !data.isObject()
                    || !data.path("totalCount").isIntegralNumber()
                    || data.path("totalCount").asLong() < 0
                    || !data.path("chatResponse").isArray()) throw GatewayException.unavailable();
                return data;
              });
    } catch (RuntimeException error) {
      throw GatewayException.unavailable();
    }
  }

  public record Query(String queryText, Integer size, String channel) {
    public Query {
      size = size == null ? 10 : size;
      channel = channel == null ? "sol-chat" : channel;
      if (queryText == null || queryText.isBlank() || size < 1 || size > 1000 || channel.isBlank())
        throw GatewayException.badRequest();
    }
  }
}
