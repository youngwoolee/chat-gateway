package com.example.chatgateway.controller;

import com.example.chatgateway.service.SessionAuthentication;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 프론트의 세션 생성·새 대화 요청을 처리한다. */
@RestController
public class ChatSessionController {
  private final SessionAuthentication auth;
  private final Clock clock;

  /** 세션 인증 처리와 응답 시각 계산에 필요한 의존성을 주입한다. */
  public ChatSessionController(SessionAuthentication auth, Clock clock) {
    this.auth = auth;
    this.clock = clock;
  }

  /** 새 대화용 세션을 생성하거나 유효한 기존 세션을 재사용한다. 쿠키 발급·만료 연장은 인증 서비스에서 처리한다. */
  @PostMapping("/api/auth/session")
  public SessionResponse create(HttpServletRequest e, HttpServletResponse response) {
    return response(auth.createOrReuse(e, response));
  }

  /** 새 대화는 유효한 회원 인증을 승계하고 세션 ID·대화 ID를 교체한다. */
  @PostMapping("/api/auth/session/restart")
  public SessionResponse restart(
      HttpServletRequest request, HttpServletResponse response, @RequestBody Restart input) {
    return response(auth.restart(request, response, input.threadId()));
  }

  public record Restart(String threadId) {}

  /** 세션 ID 원문 없이 사용자 유형과 UTC epoch milliseconds 기준 시각을 응답으로 구성한다. */
  private SessionResponse response(Session s) {
    return new SessionResponse(s.userType(), s.expiresAt(), clock.millis(), s.threadId());
  }

  public record SessionResponse(
      String userType, long expiresAt, long serverTime, String threadId) {}
}
