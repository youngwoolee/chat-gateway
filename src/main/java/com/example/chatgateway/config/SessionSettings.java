package com.example.chatgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 로그인 활동 기한과 플랫폼 공유 쿠키의 범위. */
@ConfigurationProperties("chat.session")
public record SessionSettings(
    @DefaultValue("5m") Duration idleTimeout,
    @DefaultValue("shinhancard.com") String platformDomain,
    @DefaultValue("/") String platformPath) {}
