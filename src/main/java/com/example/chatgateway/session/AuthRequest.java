package com.example.chatgateway.session;

import java.util.Objects;

/** 업무 실행 상태가 아닌, 업무별 추가 인증 등록이다. 고객 대조값은 세션 서비스에서 보호해 보관한다. */
public record AuthRequest(
    String authReqId,
    String key,
    String subject,
    String channel,
    String threadId,
    String operationId,
    String version,
    String interfaceId,
    String actionHash,
    Comparison comparison,
    long expiresAt,
    String sdkInput) {
  public record Comparison(String ssn, String iPin, String code, String usage, String content) {
    @Override
    public String toString() {
      return "Comparison[redacted]";
    }
  }

  /** key는 고객·채널·thread·업무 안에서만 재사용한다. */
  public String scopeKey() {
    return Credentials.hash(String.join("|", subject, channel, threadId, operationId, key));
  }

  /** 멱등 재등록은 내용·고객 대조값까지 동일해야 한다. */
  public boolean sameRegistration(AuthRequest other) {
    return key.equals(other.key)
        && subject.equals(other.subject)
        && channel.equals(other.channel)
        && threadId.equals(other.threadId)
        && operationId.equals(other.operationId)
        && version.equals(other.version)
        && interfaceId.equals(other.interfaceId)
        && actionHash.equals(other.actionHash)
        && Objects.equals(comparison, other.comparison);
  }

  public AuthRequest withSdk(String value) {
    return new AuthRequest(
        authReqId,
        key,
        subject,
        channel,
        threadId,
        operationId,
        version,
        interfaceId,
        actionHash,
        comparison,
        expiresAt,
        value);
  }

  @Override
  public String toString() {
    return "AuthRequest[redacted]";
  }
}
