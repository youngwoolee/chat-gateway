package com.example.chatgateway.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 제안값. 플랫폼 합의 후 설정으로 조정하며 인증 상태를 로그인 세션에 넣지 않는다. */
@ConfigurationProperties("chat.mfa")
public record MfaProperties(
    @DefaultValue("10m") Duration registrationTtl,
    @DefaultValue("2m") Duration verifiedTtl,
    @DefaultValue("8") int maxResults,
    @DefaultValue("4096") int maxHeaderBytes,
    @DefaultValue({"FINANCE_CA", "SHINHAN"}) Set<String> methods) {
  public MfaProperties {
    if (registrationTtl == null
        || registrationTtl.isNegative()
        || registrationTtl.isZero()
        || verifiedTtl == null
        || verifiedTtl.isNegative()
        || verifiedTtl.isZero()
        || maxResults < 1
        || maxResults > 32
        || maxHeaderBytes < 256
        || maxHeaderBytes > 8192) throw new IllegalArgumentException("Invalid MFA limits");
  }
}
