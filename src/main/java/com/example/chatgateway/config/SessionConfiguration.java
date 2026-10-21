package com.example.chatgateway.config;

import com.example.chatgateway.client.pfc.MockPfcClient;
import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthRequest;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.LocalAuthorityStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** local mock과 운영 연동 경계를 프로필로 분리한다. 운영 연동이 없으면 인증을 허용하지 않는 구현을 등록한다. */
@Configuration
@EnableConfigurationProperties({SessionSettings.class, MfaProperties.class})
public class SessionConfiguration {
  /** 만료 계산에 사용할 UTC 시계를 등록한다. 테스트에서는 시계를 교체해 시간 경계 조건을 검증할 수 있다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** local 프로필에서만 단일 프로세스 메모리 저장소를 등록한다. 운영 영속성·다중 인스턴스 보장은 제공하지 않는다. */
  @Bean
  @Profile("local")
  AuthorityStore localAuthority() {
    return new LocalAuthorityStore();
  }

  /** local 프로필에서만 고정 테스트 티켓과 MFA tx를 처리하는 플랫폼 mock을 등록한다. */
  @Bean
  @Profile("local")
  PfcClient localPlatform(Clock clock) {
    return new MockPfcClient(clock);
  }

  /** 운영 플랫폼 구현이 없으면 인증·쿠키 발급을 성공으로 가정하지 않고 503으로 차단한다. */
  @Bean
  @Profile("!local")
  @ConditionalOnMissingBean(PfcClient.class)
  PfcClient unavailablePlatform() {
    return new PfcClient() {
      /** 운영 PFC 미연결 상태에서는 고객 인증을 허용하지 않고 503을 반환한다. */
      public String authenticate(IdentityCookies c, String k) {
        throw GatewayException.unavailable();
      }

      /** 운영 KMS·플랫폼 미연결 상태에서는 홈페이지 로그인 쿠키를 발급하지 않는다. */
      public IdentityCookies homepage(String s, String k) {
        throw GatewayException.unavailable();
      }

      /** 운영 인증 시스템 미연결 상태에서는 MFA 도전을 생성하지 않는다. */
      public String start(AuthRequest o) {
        throw GatewayException.unavailable();
      }

      /** 운영 인증 시스템 미연결 상태에서는 tx를 승인하지 않는다. */
      public VerifiedTx verify(MfaResult t, AuthRequest o) {
        throw GatewayException.unavailable();
      }
    };
  }
}
