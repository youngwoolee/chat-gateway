package com.example.chatgateway.gateway;

import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Credentials;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class InternalServiceGuard {
  private final SessionSettings settings;

  /** B/E에서 Gateway로 호출할 때 사용하는 서비스 자격증명 설정을 주입한다. */
  public InternalServiceGuard(SessionSettings settings) {
    this.settings = settings;
  }

  /** 내부 호출의 서비스 자격증명을 비교한다. 설정 미비는 503, 자격증명 불일치는 403으로 거절한다. */
  public void require(HttpServletRequest e) {
    String header = e.getHeader("Authorization");
    if (settings.inboundCredential().isBlank()) throw GatewayException.unavailable();
    if (!Credentials.matches("Bearer " + settings.inboundCredential(), header))
      throw GatewayException.denied();
  }
}
