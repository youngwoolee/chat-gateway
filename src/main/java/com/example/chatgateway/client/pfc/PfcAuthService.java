package com.example.chatgateway.client.pfc;

import com.example.chatgateway.exception.GatewayException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 내부망 orchestrator의 동일 서비스 구현을 이식할 위치. */
@Service
@Profile("!offline")
public class PfcAuthService {
  public String getUserId(
      String systemCode, String chatbotSystemCode, String authentication, String hmac) {
    // 내부망의 기존 getUserId 및 HMAC/PFC 연동 구현으로 교체한다.
    throw GatewayException.unavailable();
  }
}
