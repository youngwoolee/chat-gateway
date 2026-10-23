package com.example.chatgateway.session;

import com.example.chatgateway.exception.GatewayException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * shortcut: single-process local fixture only; production needs an external session service with
 * atomic commands and retention.
 */
public final class LocalAuthorityStore implements AuthorityStore {
  private final Map<String, Session> sessions = new HashMap<>();
  private final Set<String> activities = new HashSet<>();

  private long guestSequence;

  /** offline에서만 쓰는 임시 발급기. 실제 분산 발급·중복 방지는 세션 서비스가 구현한다. */
  private String nextGuestId() {
    String candidate;
    do {
      if (++guestSequence > 999_999_999_999L) throw GatewayException.unavailable();
      candidate = "G" + String.format(Locale.ROOT, "%012d", guestSequence);
    } while (guestIdExists(candidate));
    return candidate;
  }

  private boolean guestIdExists(String id) {
    return sessions.values().stream().anyMatch(session -> id.equals(session.subject()));
  }

  private final Set<String> creations = new HashSet<>();

  /** 저장된 세션의 존재·만료·폐기 상태를 검사하고 활동 처리가 가능한 세션만 반환한다. */
  private Session active(String h, long now) {
    Session s = sessions.get(h);
    if (s == null || s.expiresAt() <= now || !"ACTIVE".equals(s.state()))
      throw GatewayException.unauthorized();
    return s;
  }

  /** 로컬 테스트에서 외부 폐기를 재현한다. 운영 폐기·교체는 세션 서비스가 처리한다. */
  public synchronized void revoke(String h) {

    var old = sessions.get(h);
    if (old != null) sessions.put(h, old.state("REVOKED"));
  }

  /** 세션 ID 해시로 저장된 상태를 조회한다. 반환된 값만으로 인증하지 말고 만료·채널·폐기 상태를 검사해야 한다. */
  public synchronized Session find(String h) {
    return sessions.get(h);
  }

  /** 신규 세션을 저장하고 생성 요청 키의 중복을 검사한다. 키를 받은 생성 요청은 동일 키 재시도에서 추가 활동 연장을 막아야 한다. */
  public synchronized Session create(Session s, String creationKey) {

    if ("GUEST".equals(s.userType()) && s.subject() == null)
      s =
          new Session(
              s.idHash(),
              nextGuestId(),
              s.userType(),
              s.channel(),
              s.state(),
              s.lastActivityAt(),
              s.expiresAt(),
              s.guestSubject());
    if (creationKey != null && !creations.add(s.channel() + ":" + creationKey))
      throw GatewayException.conflict();
    if (creationKey != null) activities.add(s.idHash() + ":session:" + creationKey);
    if (sessions.putIfAbsent(s.idHash(), s) != null) throw GatewayException.conflict();
    return s;
  }

  /** 세션 유효성 검사와 활동 연장을 원자적으로 처리한다. 만료된 세션은 부활시키지 않으며 같은 활동 키는 추가 연장하지 않는다. */
  public synchronized Session touch(String h, long now, long ttl, String key) {

    Session s = active(h, now);
    if (key != null && !activities.add(h + ":" + key)) return s;
    Session next = s.touch(now, ttl);
    sessions.put(h, next);
    return next;
  }

  /** 게스트 유효성을 다시 검사하고 폐기·회원 저장을 원자 처리하여 중복 교체를 차단한다. */
  public synchronized Session replaceGuest(String h, String key, Session replacement, long now) {
    Session guest = active(h, now);
    if (guest.member()
        || !replacement.member()
        || !"ACTIVE".equals(replacement.state())
        || !guest.channel().equals(replacement.channel())
        || !guest.subject().equals(replacement.guestSubject())
        || replacement.expiresAt() <= now
        || sessions.containsKey(replacement.idHash())) throw GatewayException.conflict();
    sessions.put(h, guest.state("REVOKED"));
    sessions.put(replacement.idHash(), replacement);
    if (key != null) {
      activities.add(replacement.idHash() + ":session:" + key);
      activities.add(replacement.idHash() + ":chat:" + key);
    }
    return replacement;
  }
}
