package com.example.chatgateway.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 세션·실행 문맥·플랫폼 쿠키·내부 호출 정책을 바인딩한다. 자격증명 원문을 로그에 출력하지 않는다.
 *
 * @param idleTimeout 마지막 인정 활동으로부터의 세션 유효시간. 기본 5분
 * @param executionTimeout 이미 시작한 실행 문맥의 별도 유효시간. 기본 10분이며 세션 만료와 구분한다.
 * @param platformDomain 플랫폼 공유 쿠키를 생성·삭제할 Domain. 실제 홈페이지 계약과 일치해야 한다.
 * @param platformPath 플랫폼 공유 쿠키의 Path. 삭제 시에도 발급 때와 동일해야 한다.
 * @param backendCredential Gateway → B/E 호출의 서비스 자격증명. 미설정 시 해당 호출은 503
 * @param inboundCredential B/E → Gateway 내부 API의 서비스 자격증명. 미설정 시 해당 요청은 503
 * @param allowedInterfaces 업무별 추가 인증을 등록할 수 있는 인터페이스 목록. 비어 있으면 모두 차단한다.
 */
@ConfigurationProperties("chat.session")
public record SessionSettings(
    @DefaultValue("5m") Duration idleTimeout,
    @DefaultValue("10m") Duration executionTimeout,
    @DefaultValue("shinhancard.com") String platformDomain,
    @DefaultValue("/") String platformPath,
    @DefaultValue("") String backendCredential,
    @DefaultValue("") String inboundCredential,
    @DefaultValue Set<String> allowedInterfaces) {}
