package com.example.chatgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway에서 사용하는 B/E 연결 설정을 바인딩한다.
 *
 * @param backend B/E 주소와 연결·응답 대기 정책
 */
@ConfigurationProperties("chat")
public record ChatProperties(Backend backend) {
  /**
   * Agent B/E 연결 정보. 연결 시간 제한은 Gateway HTTP 클라이언트 설정에서도 참조한다.
   *
   * @param baseUrl B/E 내부 API의 기본 주소
   * @param connectTimeoutMillis TCP 연결 대기 제한(밀리초)
   * @param responseTimeout 프록시의 응답 읽기 간격 제한.
   */
  public record Backend(
      String baseUrl,
      @DefaultValue("3000") int connectTimeoutMillis,
      @DefaultValue("30s") Duration responseTimeout,
      @DefaultValue("100") int maxStreams) {}
}
