package com.example.chatgateway.config;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.exception.GatewayException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 실제 서비스 연동의 공통 설정. 운영 연동이 없으면 인증을 허용하지 않는 구현을 등록한다. */
@Configuration
@EnableConfigurationProperties({SessionSettings.class, MfaProperties.class})
public class SessionConfiguration {
  /** 만료 계산에 사용할 UTC 시계를 등록한다. 테스트에서는 시계를 교체해 시간 경계 조건을 검증할 수 있다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** 운영 플랫폼 구현이 없으면 인증·쿠키 발급을 성공으로 가정하지 않고 503으로 차단한다. */
  @Bean
  @Profile("!offline")
  @ConditionalOnMissingBean(PfcClient.class)
  PfcClient unavailablePlatform() {
    return new PfcClient() {
      /** 운영 PFC 미연결 상태에서는 고객 인증을 허용하지 않고 503을 반환한다. */
      public String authenticate(IdentityCookies c) {
        throw GatewayException.unavailable();
      }

      /** 운영 KMS·플랫폼 미연결 상태에서는 홈페이지 로그인 쿠키를 발급하지 않는다. */
      public IdentityCookies homepage(String s) {
        throw GatewayException.unavailable();
      }

      public String verify(MfaInput input, String state) {
        throw GatewayException.unavailable();
      }
    };
  }
}
