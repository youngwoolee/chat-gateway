package com.example.chatgateway.session;

import com.example.chatgateway.exception.GatewayException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * shortcut: single-process local fixture only; production needs an external session service with
 * atomic commands and retention.
 */
public final class LocalAuthorityStore implements AuthorityStore {
  private final Map<String, Session> sessions = new HashMap<>();
  private final Map<String, ExecutionGrant> grants = new HashMap<>();
  private final Map<String, AuthRequest> requests = new HashMap<>();
  private final Map<String, String> registrationKeys = new HashMap<>();
  private final Set<String> consumedRequests = new HashSet<>();
  private final Map<String, ResumeReceipt> receipts = new HashMap<>();
  private final Map<String, String> usedTx = new HashMap<>();
  private final Set<String> activities = new HashSet<>();
  private final Map<String, RestartReceipt> restarts = new HashMap<>();

  private record RestartReceipt(String threadId, String replacementHash) {}

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

    if (creationKey != null && !creations.add(creationKey)) throw GatewayException.conflict();
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
        || !guest.threadId().equals(replacement.threadId())
        || !guest.subject().equals(replacement.guestSubject())
        || replacement.expiresAt() <= now
        || sessions.containsKey(replacement.idHash())) throw GatewayException.conflict();
    sessions.put(h, guest.state("REVOKED"));
    sessions.put(replacement.idHash(), replacement);
    activities.add(replacement.idHash() + ":session:" + key);
    activities.add(replacement.idHash() + ":chat:" + key);
    return replacement;
  }

  /** 폐기·생성·재시도 기록을 함께 확정하며 신규/기존 쿠키의 동일 요청을 모두 처리한다. */
  public synchronized Session restart(RestartSession command) {
    String requestKey = command.hash() + ":" + command.key();
    RestartReceipt previous = restarts.get(requestKey);
    if (previous != null) {
      if (!previous.threadId().equals(command.threadId())) throw GatewayException.conflict();
      return active(previous.replacementHash(), command.now());
    }
    Session old = active(command.hash(), command.now());
    if (old.threadId().equals(command.threadId())
        || command.ttl() <= 0
        || sessions.containsKey(command.replacementHash())) throw GatewayException.conflict();
    Session replacement =
        new Session(
            command.replacementHash(),
            old.member() ? old.subject() : "guest-" + Credentials.random(),
            old.userType(),
            old.channel(),
            "ACTIVE",
            command.now(),
            command.now() + command.ttl(),
            null,
            command.threadId());
    RestartReceipt receipt = new RestartReceipt(command.threadId(), replacement.idHash());
    sessions.put(old.idHash(), old.state("REVOKED"));
    sessions.put(replacement.idHash(), replacement);
    restarts.put(requestKey, receipt);
    restarts.put(replacement.idHash() + ":" + command.key(), receipt);
    return replacement;
  }

  /** 브라우저 세션과 별개인 실행 자격증명의 해시와 신원·thread·실행 기한을 저장한다. */
  public synchronized ExecutionGrant saveGrant(ExecutionGrant g) {

    grants.put(g.hash(), g);
    return g;
  }

  /** 실행 자격증명을 조회하며 부재·만료는 인증 오류로 거절한다. */
  public synchronized ExecutionGrant grant(String h, long now) {

    var g = grants.get(h);
    if (g == null || g.expiresAt() <= now) throw GatewayException.unauthorized();
    return g;
  }

  /** 같은 key는 최초 등록을 반환하며 변경된 입력이나 만료 등록은 재발급하지 않는다. */
  public synchronized AuthRequest registerAuth(AuthRequest request, long now) {
    String oldId = registrationKeys.get(request.scopeKey());
    if (oldId != null) {
      AuthRequest old = requests.get(oldId);
      if (!old.sameRegistration(request)) throw GatewayException.conflict();
      if (old.expiresAt() <= now) throw GatewayException.mfaExpired();
      return old;
    }
    if (request.expiresAt() <= now || requests.containsKey(request.authReqId()))
      throw GatewayException.conflict();
    requests.put(request.authReqId(), request);
    registrationKeys.put(request.scopeKey(), request.authReqId());
    return request;
  }

  public synchronized AuthRequest authRequest(String id) {
    var value = requests.get(id);
    if (value == null) throw GatewayException.mfaRequired();
    return value;
  }

  public synchronized AuthRequest saveSdkInput(String id, String sdk, long now) {
    var value = authRequest(id);
    if (value.expiresAt() <= now) throw GatewayException.mfaExpired();
    if (sdk == null || sdk.isBlank()) throw GatewayException.badGateway();
    if (value.sdkInput() == null) {
      value = value.withSdk(sdk);
      requests.put(id, value);
    }
    return value;
  }

  public synchronized ResumeReceipt findResume(String hash, String key) {
    return receipts.get(hash + ":" + key);
  }

  /** 모든 검사를 쓰기 전에 끝내므로 일부 실패·경쟁 요청에서 부분 소비가 발생하지 않는다. */
  public synchronized ResumeReceipt consumeAuth(ConsumeAuth command) {
    var session = active(command.sessionHash(), command.now());
    if (!session.member()) throw GatewayException.denied();
    var previous = findResume(command.sessionHash(), command.key());
    if (previous != null) {
      if (!previous.fingerprint().equals(command.fingerprint())) throw GatewayException.conflict();
      if (previous.expiresAt() <= command.now()) throw GatewayException.mfaExpired();
      return previous;
    }
    if (command.proofs() == null || command.proofs().isEmpty()) throw GatewayException.badRequest();
    var ids = new HashSet<String>();
    var hashes = new HashSet<String>();
    long until = Long.MAX_VALUE;
    for (var proof : command.proofs()) {
      var request = authRequest(proof.authReqId());
      if (!session.subject().equals(request.subject())
          || !session.channel().equals(request.channel())
          || !command.threadId().equals(request.threadId())) throw GatewayException.mfaInvalid();
      if (consumedRequests.contains(request.authReqId())) throw GatewayException.conflict();
      if (request.expiresAt() <= command.now() || proof.expiresAt() <= command.now())
        throw GatewayException.mfaFailure("MFA_EXPIRED", List.of(request.authReqId()));
      if (!ids.add(request.authReqId())
          || !hashes.add(proof.proofHash())
          || usedTx.containsKey(proof.proofHash()))
        throw GatewayException.mfaFailure("MFA_INVALID", List.of(request.authReqId()));
      until = Math.min(until, Math.min(request.expiresAt(), proof.expiresAt()));
    }
    var receipt =
        new ResumeReceipt(
            command.sessionHash(),
            command.key(),
            command.fingerprint(),
            command.proofs().stream().map(Proof::authReqId).toList(),
            until);
    for (var proof : command.proofs()) {
      consumedRequests.add(proof.authReqId());
      usedTx.put(proof.proofHash(), proof.authReqId());
    }
    receipts.put(command.sessionHash() + ":" + command.key(), receipt);
    return receipt;
  }
}
