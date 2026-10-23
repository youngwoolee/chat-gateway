package com.example.chatgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("chat.mfa")
public record MfaProperties(@DefaultValue("4096") int maxHeaderBytes) {
  public MfaProperties {
    if (maxHeaderBytes < 256 || maxHeaderBytes > 8192)
      throw new IllegalArgumentException("Invalid MFA header limit");
  }
}
