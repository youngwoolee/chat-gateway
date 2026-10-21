package com.example.chatgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 세션 서비스 탐색 이름·서비스 인증·통신 제한. Redis/Hazelcast 설정은 포함하지 않는다. */
@ConfigurationProperties("chat.session-service")
public record SessionServiceProperties(
    @DefaultValue("agent-session-service") String serviceId,
    @DefaultValue("") String credential,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration responseTimeout) {
  public SessionServiceProperties {
    if (serviceId == null
        || !serviceId.matches("[A-Za-z0-9-]+")
        || connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || responseTimeout == null
        || responseTimeout.isNegative()
        || responseTimeout.isZero())
      throw new IllegalArgumentException("Invalid session service configuration");
  }
}
