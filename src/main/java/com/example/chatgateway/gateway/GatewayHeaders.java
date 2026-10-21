package com.example.chatgateway.gateway;

import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.session.Session;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;

/** 공식 Gateway 헤더 필터 확장점에서 서비스 신뢰 경계만 적용한다. */
@Configuration
public class GatewayHeaders {
  private static final Set<String> HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade");

  /** 클라이언트 신원을 지운 뒤 인증 필터가 확정한 신원으로 교체한다. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RequestHttpHeadersFilter authenticatedHeaders(SessionSettings settings) {
    return (input, request) -> {
      var headers = clean(input, true);
      var session = (Session) request.servletRequest().getAttribute(ChatGatewayFilter.SESSION);
      headers.set("Authorization", "Bearer " + settings.backendCredential());
      headers.set("X-Authenticated-User-Id", session.subject());
      headers.set("X-Authenticated-User-Type", session.userType());
      headers.set("X-Authenticated-Channel-Id", session.channel());
      String guest = session.member() ? session.guestSubject() : session.subject();
      if (guest != null) headers.set("X-Authenticated-Guest-Id", guest);
      String context = (String) request.servletRequest().getAttribute(ChatGatewayFilter.CONTEXT);
      if (context != null) headers.set("X-Auth-Context", context);
      String verified = (String) request.servletRequest().getAttribute(ChatGatewayFilter.VERIFIED);
      if (verified != null) headers.set("X-Verified-Auth-Requests", verified);
      headers.set("X-Request-Id", RequestIdFilter.id(request.servletRequest()));
      return headers;
    };
  }

  /** B/E가 연결 전용 헤더와 Gateway 인증 시각을 주입하지 못하게 한다. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  ResponseHttpHeadersFilter responseHeaders() {
    return (headers, response) -> clean(headers, false);
  }

  private HttpHeaders clean(HttpHeaders input, boolean request) {
    var excluded = new HashSet<>(HOP);
    for (String value : input.getOrEmpty("Connection")) nominated(excluded, value);
    var output = new HttpHeaders();
    input.forEach(
        (name, values) -> {
          String lower = name.toLowerCase(Locale.ROOT);
          if (excluded.contains(lower)) return;
          if (request
              && (identity(lower)
                  || lower.equals("expect")
                  || lower.equals("host")
                  || lower.equals("content-length"))) return;
          if (!request && (lower.equals("x-chat-auth-expires-at") || lower.equals("x-server-time")))
            return;
          output.addAll(name, values);
        });
    return output;
  }

  /** Connection이 지목한 추가 hop-by-hop 헤더도 제거한다. */
  private static void nominated(Set<String> excluded, String value) {
    for (String name : value.split(",")) excluded.add(name.trim().toLowerCase(Locale.ROOT));
  }

  /** 브라우저의 신원·서비스 인증·전달 경로 주장은 신뢰하지 않는다. */
  private boolean identity(String name) {
    return name.equals("x-verified-login")
        || name.equals("x-requested-login")
        || name.equals("x-verified-auth-requests")
        || name.equals("cookie")
        || name.equals("authorization")
        || name.startsWith("x-auth")
        || name.startsWith("x-user")
        || name.startsWith("x-channel")
        || name.startsWith("x-internal")
        || name.startsWith("x-guest")
        || name.startsWith("x-mfa")
        || name.equals("x-is-login")
        || name.equals("x-operation-id")
        || name.equals("forwarded")
        || name.startsWith("x-forwarded-");
  }
}
