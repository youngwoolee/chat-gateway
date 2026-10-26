package com.example.chatgateway.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  private final java.time.Clock clock;

  public RequestIdFilter(java.time.Clock clock) {
    this.clock = clock;
  }

  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

  /** 허용 형식의 추적 ID만 보존하고 후속 프록시가 사용할 요청 속성에 저장한다. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("X-XSS-Protection", "0");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Expires", "0");
    if (request.isSecure())
      response.setHeader("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains");
    response.setHeader("X-Server-Time", Long.toString(clock.millis()));
    String supplied = request.getHeader("X-Request-Id");
    request.setAttribute(
        ATTRIBUTE,
        supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,128}")
            ? supplied
            : UUID.randomUUID().toString());
    chain.doFilter(request, response);
  }

  /** 필터를 거치지 않은 단위 호출도 별도 추적 ID를 갖게 한다. */
  public static String id(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value == null ? UUID.randomUUID().toString() : value.toString();
  }
}
