package com.example.chatgateway.session;

/**
 * 외부 세션 서비스에 위임할 상태 처리 계약. Gateway는 저장소 종류와 접근 방식을 알지 않는다. 원자 명령·재사용 방지 보장은 서비스 측 책임이다. 대화와 업무 인증
 * 티켓은 저장하지 않는다.
 */
public interface AuthorityStore {
  /** 세션 ID 해시로 저장된 상태를 조회한다. 부재 시 null을 반환한다. 반환된 값만으로 인증하지 말고 만료·채널·폐기 상태를 검사해야 한다. */
  Session find(String hash);

  /**
   * 신규 게스트는 subject=null로 요청하며 서비스가 G 시작13자리 ID를 발급·저장해 반환한다. 회원 PID는 보존한다. 채널별 생성 요청 키의 중복을 검사한다.
   * creationKey는 선택값이며 G/W의 세션 생성 요청은 null로 전달한다.
   */
  Session create(Session session, String creationKey);

  /** 외부 인증 티켓을 소비하기 전에 최종 저장소에 접근 가능한지 확인한다. 이후 쓰기의 성공까지 보장하는 검사는 아니다. */
  default void checkAvailable() {
    find("availability-probe");
  }

  /** 세션 유효성 검사와 활동 연장을 원자적으로 처리한다. G/W는 activityKey=null로 수신한 활동마다 연장하며 만료된 세션은 부활시키지 않는다. */
  Session touch(String hash, long now, long ttl, String activityKey);

  /** 유효한 게스트 폐기와 회원 세션 생성을 한 번만 원자 처리한다. 기존 게스트 신원도 일치해야 한다. */
  Session replaceGuest(String hash, String key, Session replacement, long now);
}
