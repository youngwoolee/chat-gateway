package com.example.chatgateway.client.pfc;

import com.example.chatgateway.session.AuthRequest;
import com.example.chatgateway.session.AuthRequest.Comparison;

/** 내부 PFC/KMS 연동 계약. 실제 인증 원문은 로그에 남기지 않는다. */
public interface PfcClient {
  String authenticate(IdentityCookies cookies, String idempotencyKey);

  IdentityCookies homepage(String subject, String idempotencyKey);

  /** authReqId 기준 멱등 시작. SDK용 암호화 정보만 반환하고 평문 고객 대조값은 반환하지 않는다. */
  String start(AuthRequest request);

  /** 수단별 비소비 검증. 진위·기한·요청·고객 대조값은 플랫폼이 확인한 값으로 반환해야 한다. */
  VerifiedTx verify(MfaResult result, AuthRequest request);

  record IdentityCookies(String systemCode, String authentication, String verify) {}

  record MfaResult(String authReqId, String tranID, String txId, String method) {
    @Override
    public String toString() {
      return "MfaResult[redacted]";
    }
  }

  record VerifiedTx(
      String subject,
      String authReqId,
      String tranID,
      String txId,
      String method,
      Comparison comparison,
      long expiresAt) {
    @Override
    public String toString() {
      return "VerifiedTx[redacted]";
    }
  }
}
