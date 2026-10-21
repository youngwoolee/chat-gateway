package com.example.chatgateway.controller;

import com.example.chatgateway.gateway.InternalServiceGuard;
import com.example.chatgateway.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** B/E의 인증 요청 등록만 노출한다. FE 인증 결과는 채팅 resume 필터에서 검증한다. */
@RestController
public class OperationController {
  private final MfaService mfa;
  private final InternalServiceGuard guard;

  public OperationController(MfaService mfa, InternalServiceGuard guard) {
    this.mfa = mfa;
    this.guard = guard;
  }

  /** 서비스 인증·문맥과 등록 key로 복수 인증을 등록한다. */
  @PostMapping("/internal/v1/auth/operations")
  public MfaService.Registered register(
      HttpServletRequest request, @RequestBody MfaService.Registration input) {
    guard.require(request);
    return mfa.register(request.getHeader("X-Auth-Context"), input);
  }
}
