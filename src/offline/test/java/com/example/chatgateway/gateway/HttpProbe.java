package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.util.LinkedMultiValueMap;

/** 실제 TCP 요청으로 기존 HTTP 계약 단언을 재사용하는 테스트 전용 도구다. */
class HttpProbe {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final String origin;
  final String channel;

  HttpProbe(String origin) {
    this(origin, null);
  }

  HttpProbe(String origin, String channel) {
    this.origin = origin;
    this.channel = channel;
  }

  Request post() {
    return new Request("POST");
  }

  Request get() {
    return new Request("GET");
  }

  class Request {
    final String method;
    String path;
    Object body;
    HttpHeaders headers = new HttpHeaders();

    Request(String method) {
      this.method = method;
    }

    Request uri(String path) {
      this.path = path;
      if (channel != null && path.startsWith("/api/") && !path.contains("channelId="))
        this.path += (path.contains("?") ? "&" : "?") + "channelId=" + channel;
      return this;
    }

    Request header(String name, String value) {
      headers.add(name, value);
      return this;
    }

    Request headers(Consumer<HttpHeaders> consumer) {
      consumer.accept(headers);
      return this;
    }

    Request cookie(String name, String value) {
      headers.add("Cookie", name + "=" + value);
      return this;
    }

    Request contentType(MediaType type) {
      headers.setContentType(type);
      return this;
    }

    Request bodyValue(Object value) {
      body = value;
      if (headers.getContentType() == null) headers.setContentType(MediaType.APPLICATION_JSON);
      return this;
    }

    Reply exchange() {
      try {
        byte[] bytes =
            body == null
                ? new byte[0]
                : body instanceof String text
                    ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : JSON.writeValueAsBytes(body);
        var request =
            HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        return new Reply(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }

  static class Reply {
    final HttpResponse<InputStream> response;
    byte[] bytes;
    String jsonPath;
    boolean status;

    Reply(HttpResponse<InputStream> response) {
      this.response = response;
    }

    Reply expectStatus() {
      status = true;
      return this;
    }

    Reply isOk() {
      return isEqualTo(200);
    }

    Reply isNotFound() {
      return isEqualTo(404);
    }

    Reply isUnauthorized() {
      return isEqualTo(401);
    }

    Reply isForbidden() {
      return isEqualTo(403);
    }

    Reply expectHeader() {
      status = false;
      return this;
    }

    Reply doesNotExist(String name) {
      assertThat(response.headers().allValues(name)).isEmpty();
      return this;
    }

    Reply exists(String name) {
      assertThat(response.headers().allValues(name)).isNotEmpty();
      return this;
    }

    Reply expectBody() {
      status = false;
      return this;
    }

    Reply expectBody(Class<?> type) {
      return expectBody();
    }

    Reply jsonPath(String path) {
      jsonPath = path;
      return this;
    }

    Reply isEqualTo(Object expected) {
      if (status) assertThat(response.statusCode()).isEqualTo(expected);
      else if (jsonPath != null) {
        Object value =
            JsonPath.read(
                new String(getResponseBody(), java.nio.charset.StandardCharsets.UTF_8), jsonPath);
        if (value instanceof Number a && expected instanceof Number b)
          assertThat(a.longValue()).isEqualTo(b.longValue());
        else assertThat(value).isEqualTo(expected);
      } else
        assertThat(new String(getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(expected);
      return this;
    }

    Reply returnResult() {
      return this;
    }

    Reply returnResult(Class<?> type) {
      return this;
    }

    byte[] getResponseBody() {
      try {
        if (bytes == null) bytes = response.body().readAllBytes();
        return bytes;
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }

    LinkedMultiValueMap<String, ResponseCookie> getResponseCookies() {
      var result = new LinkedMultiValueMap<String, ResponseCookie>();
      for (String header : response.headers().allValues("Set-Cookie")) {
        var cookie = java.net.HttpCookie.parse(header).getFirst();
        result.add(
            cookie.getName(),
            ResponseCookie.from(cookie.getName(), cookie.getValue())
                .domain(cookie.getDomain())
                .path(cookie.getPath())
                .httpOnly(cookie.isHttpOnly())
                .secure(cookie.getSecure())
                .maxAge(cookie.getMaxAge())
                .build());
      }
      return result;
    }
  }
}
