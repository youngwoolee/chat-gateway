package com.example.chatgateway.client.agent;

import com.example.chatgateway.gateway.RequestIdFilter;
import com.example.chatgateway.service.ChatAuthenticationService.AuthenticatedChat;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** HTTP 중계의 신원·연결 헤더 경계를 관리한다. */
@Component
public class BackendHeaders {
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

  /** 외부 신원 주장을 제거하고 인증 서비스가 확정한 신원만 전달한다. */
  public HttpHeaders request(HttpServletRequest request, AuthenticatedChat authenticated) {
    var input = new HttpHeaders();
    Collections.list(request.getHeaderNames())
        .forEach(name -> input.put(name, Collections.list(request.getHeaders(name))));
    var headers = clean(input, true);
    var session = authenticated.session();
    headers.set("X-Authenticated-User-Id", session.subject());
    headers.set("X-Authenticated-Channel-Id", session.channel());
    String guest = session.member() ? session.guestSubject() : session.subject();
    if (guest != null) headers.set("X-Authenticated-Guest-Id", guest);
    if (authenticated.verifiedRequestId() != null)
      headers.set("X-Verified-Request-Id", authenticated.verifiedRequestId());
    headers.set("X-Request-Id", RequestIdFilter.id(request));
    return headers;
  }

  /** 연결 전용 헤더와 B/E가 보낸 인증 만료 헤더를 제거한다. */
  public HttpHeaders response(HttpHeaders input) {
    return clean(input, false);
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
                  || lower.equals("idempotency-key")
                  || lower.equals("expect")
                  || lower.equals("host")
                  || lower.equals("content-length"))) return;
          if (!request
              && (lower.equals("x-chat-user-type")
                  || lower.equals("x-chat-auth-expires-at")
                  || lower.equals("x-server-time"))) return;
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
    return name.startsWith("x-verified-")
        || name.equals("x-requested-login")
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
