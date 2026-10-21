package com.example.chatgateway.session;

import com.example.chatgateway.exception.GatewayException;
import java.util.List;

/**
 * 외부 세션 서비스에 위임할 상태 처리 계약. Gateway는 저장소 종류와 접근 방식을 알지 않는다. 원자 명령·재사용 방지 보장은 서비스 측 책임이다. 실행 문맥·MFA
 * 기록도 agent-session-service에서 로그인 세션과 별도 수명으로 관리한다.
 */
public interface AuthorityStore {
  /** 세션 ID 해시로 저장된 상태를 조회한다. 부재 시 null을 반환한다. 반환된 값만으로 인증하지 말고 만료·채널·폐기 상태를 검사해야 한다. */
  Session find(String hash);

  /** 신규 세션을 저장하고 생성 요청 키의 중복을 검사한다. 키를 받은 생성 요청은 동일 키 재시도에서 추가 활동 연장을 막아야 한다. */
  Session create(Session session, String creationKey);

  /** 외부 인증 티켓을 소비하기 전에 최종 저장소에 접근 가능한지 확인한다. 이후 쓰기의 성공까지 보장하는 검사는 아니다. */
  default void checkAvailable() {
    find("availability-probe");
  }

  /** 세션 유효성 검사와 활동 연장을 원자적으로 처리한다. 만료된 세션은 부활시키지 않으며 같은 활동 키는 추가 연장하지 않는다. */
  Session touch(String hash, long now, long ttl, String activityKey);

  /** 유효한 게스트 폐기와 회원 세션 생성을 한 번만 원자 처리한다. 기존 게스트 신원도 일치해야 한다. */
  Session replaceGuest(String hash, String key, Session replacement, long now);

  /** 유효 신원만 승계하여 새 대화로 원자 교체한다. 동일 key 재시도는 최초 결과를 반환한다. */
  Session restart(RestartSession command);

  record RestartSession(
      String hash, String key, String threadId, String replacementHash, long now, long ttl) {}

  /** 브라우저 세션과 별개인 실행 자격증명의 해시와 신원·thread·실행 기한을 저장한다. */
  ExecutionGrant saveGrant(ExecutionGrant grant);

  /** 실행 자격증명을 조회하며 부재·만료는 인증 오류로 거절한다. */
  ExecutionGrant grant(String hash, long now);

  /** 등록 key 범위의 입력을 비교하고 동일 요청은 같은 등록을 반환한다. */
  AuthRequest registerAuth(AuthRequest request, long now);

  /** 인증 요청을 조회한다. 부재는 MFA_REQUIRED이며 원문 고객값을 외부로 반환하지 않는다. */
  AuthRequest authRequest(String id);

  /** 같은 인증 요청의 SDK 입력은 최초 값으로 고정한다. PFC 시작도 authReqId로 멱등 처리해야 한다. */
  AuthRequest saveSdkInput(String id, String sdkInput, long now);

  /** 동일 resume 재시도의 기존 일괄 소비 영수증을 조회한다. 부재는 null이다. */
  ResumeReceipt findResume(String sessionHash, String key);

  /** 세션·모든 등록·증명 재사용을 검사한 뒤 전부 소비하고 영수증을 하나의 원자 명령으로 저장한다. */
  ResumeReceipt consumeAuth(ConsumeAuth command);

  record Proof(String authReqId, String proofHash, long expiresAt) {}

  record ConsumeAuth(
      String sessionHash,
      String key,
      String fingerprint,
      String threadId,
      List<Proof> proofs,
      long now) {}

  record ResumeReceipt(
      String sessionHash,
      String key,
      String fingerprint,
      List<String> authReqIds,
      long expiresAt) {}

  record ExecutionGrant(
      String hash,
      String subject,
      String userType,
      String channel,
      String threadId,
      long expiresAt) {
    /** 업무별 인증 요청에 필요한 회원 신원과 thread를 확인한다. */
    public void requireMemberThread() {
      if (!"MEMBER".equals(userType) || threadId == null) throw GatewayException.denied();
    }
  }
}
