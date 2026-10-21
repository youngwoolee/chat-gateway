package com.example.chatgateway.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SameOriginFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

  /** 공개 /api 변경 요청의 Origin과 Fetch Metadata를 검사한다. 내부 경로는 별도 서비스 인증을 사용하며 조회 요청은 이 검사를 생략한다. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest exchange, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (SAFE.contains(exchange.getMethod()) || !exchange.getRequestURI().startsWith("/api/")) {
      chain.doFilter(exchange, response);
      return;
    }
    String fetchSite = exchange.getHeader("Sec-Fetch-Site");
    if (fetchSite != null
        && !Set.of("same-origin", "same-site", "none").contains(fetchSite.toLowerCase())) {
      forbidden(response);
      return;
    }
    String origin = exchange.getHeader("Origin");
    if (origin == null) {
      forbidden(response);
      return;
    }
    URI uri;
    try {
      uri = URI.create(origin);
    } catch (IllegalArgumentException e) {
      forbidden(response);
      return;
    }
    var request = URI.create(exchange.getRequestURL().toString());
    int originPort = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : uri.getPort();
    int requestPort = request.getPort() < 0 ? defaultPort(request.getScheme()) : request.getPort();
    if (!equals(uri.getScheme(), request.getScheme())
        || !equals(uri.getHost(), request.getHost())
        || originPort != requestPort) {
      forbidden(response);
      return;
    }
    chain.doFilter(exchange, response);
  }

  /** Origin 검증 실패를 공통 형태의 403 JSON으로 반환하고 응답 캐시를 막는다. */
  private void forbidden(HttpServletResponse response) throws IOException {
    response.setStatus(403);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(
            "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\",\"message\":\"\"}}"
                .getBytes(StandardCharsets.UTF_8));
  }

  /** scheme·host를 대소문자 구분 없이 비교하며 누락된 값은 일치로 간주하지 않는다. */
  private static boolean equals(String a, String b) {
    return a != null && a.equalsIgnoreCase(b);
  }

  /** 명시적 포트가 없는 Origin 비교를 위해 HTTPS는 443, 그 외는 80으로 정규화한다. */
  private static int defaultPort(String scheme) {
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }
}
