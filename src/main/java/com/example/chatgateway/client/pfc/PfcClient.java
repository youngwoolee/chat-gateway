package com.example.chatgateway.client.pfc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** PFC 어댑터는 티켓·식별값·상태 쿠키를 검증/소비하고 검증한 회원 PID를 반환한다. */
public interface PfcClient {
  /** 플랫폼 쿠키를 검증하여 회원 PID를 반환한다. */
  String getUserId(IdentityCookies cookies);

  /** 홈페이지 로그인 연동에 사용할 _I 쿠키 값을 생성한다. */
  IdentityCookies createLoginCookie(String subject);

  /** 업무 인증 티켓을 검증하여 인증된 회원 PID를 반환한다. */
  String verifyTicketId(MfaInput input, String state);

  record IdentityCookies(String systemCode, String authentication, String verify) {
    @Override
    public String toString() {
      return "IdentityCookies[redacted]";
    }
  }

  record MfaInput(
      JsonNode identifier, List<String> ticketIds, String code, String source, String componentId) {
    @Override
    public String toString() {
      return "MfaInput[redacted]";
    }
  }
}
