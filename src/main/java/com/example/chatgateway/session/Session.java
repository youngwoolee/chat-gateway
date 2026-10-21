package com.example.chatgateway.session;

/** 로그인 상태와 검증된 신원. guestSubject는 B/E의 대화 소유권 확인에 사용할 이전 게스트 신원이다. */
public record Session(
    String idHash,
    String subject,
    String userType,
    String channel,
    String state,
    long lastActivityAt,
    long expiresAt,
    String guestSubject,
    String threadId) {
  /** 인증된 회원 세션인지 판별한다. */
  public boolean member() {
    return "MEMBER".equals(userType);
  }

  /** 신원은 유지하고 활동 만료만 갱신한다. */
  public Session touch(long now, long ttl) {
    return new Session(
        idHash, subject, userType, channel, state, now, now + ttl, guestSubject, threadId);
  }

  /** 신원·기한을 유지하고 세션 상태를 바꾼다. */
  public Session state(String value) {
    return new Session(
        idHash,
        subject,
        userType,
        channel,
        value,
        lastActivityAt,
        expiresAt,
        guestSubject,
        threadId);
  }
}
