package com.example.chatgateway.session;

/** 채널별 로그인 신원과 활동 기한. 대화 상태는 B/E가 관리한다. */
public record Session(
    String idHash,
    String subject,
    String userType,
    String channel,
    String state,
    long lastActivityAt,
    long expiresAt,
    String guestSubject) {
  public boolean member() {
    return "MEMBER".equals(userType);
  }

  public Session touch(long now, long ttl) {
    return new Session(idHash, subject, userType, channel, state, now, now + ttl, guestSubject);
  }

  public Session state(String value) {
    return new Session(
        idHash, subject, userType, channel, value, lastActivityAt, expiresAt, guestSubject);
  }
}
