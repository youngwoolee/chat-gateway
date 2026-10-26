package com.example.chatgateway.client.pfc;

import com.example.chatgateway.exception.GatewayException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 내부망 orchestrator의 KMS/PFC 쿠키 생성 구현을 이식할 위치. */
@Service
@Profile("!offline")
public class CookieService {
  public LoginCookieResult createLoginCookie(String userId) {
    // 내부망의 기존 createLoginCookie 및 KMS/PFC 연동 구현으로 교체한다.
    throw GatewayException.unavailable();
  }

  public record LoginCookieResult(
      String platformSystemCode, String platformAuthentication, String platformVerify) {
    @Override
    public String toString() {
      return "LoginCookieResult[redacted]";
    }
  }
}
