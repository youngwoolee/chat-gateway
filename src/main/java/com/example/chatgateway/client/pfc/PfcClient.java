package com.example.chatgateway.client.pfc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** PFC 어댑터는 티켓·식별값·상태 쿠키를 검증/소비하고 검증한 회원 PID를 반환한다. */
public interface PfcClient {
  String authenticate(IdentityCookies cookies);

  IdentityCookies homepage(String subject);

  String verify(MfaInput input, String state);

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
