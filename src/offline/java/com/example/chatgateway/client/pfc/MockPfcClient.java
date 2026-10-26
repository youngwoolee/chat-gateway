package com.example.chatgateway.client.pfc;

import com.example.chatgateway.exception.GatewayException;

/** Explicit local fixture. Registered only with the offline profile. */
public final class MockPfcClient implements PfcClient {
  /** 반복 사용 가능한 offline 전용 고정 입력을 P000000001로 인증한다. */
  public String getUserId(IdentityCookies c) {
    if (!c.systemCode().equals("DEMO")
        || !c.authentication().equals("demo-ticket")
        || !c.verify().equals("demo-proof")) throw GatewayException.unauthorized();
    return "P000000001";
  }

  /** local 테스트용 _I 값을 반환한다. 실제 홈페이지의 인증 자격증명으로 사용할 수 없다. */
  public IdentityCookies createLoginCookie(String subject) {
    return new IdentityCookies("CBA", "local-homepage-ticket", "local-proof");
  }

  /** shortcut: offline 고정 티켓만 검증한다. 운영은 PFC의 소비·만료 정책을 사용한다. */
  public String verifyTicketId(MfaInput input, String state) {
    if (!"demo-state".equals(state)
        || !input.ticketIds().equals(java.util.List.of("demo-mfa-ticket"))
        || !"demo-code".equals(input.code())
        || !"demo-ssn".equals(input.identifier().path("ssn").asText())
        || !"demo-ipin".equals(input.identifier().path("iPin").asText()))
      throw GatewayException.mfaInvalid();
    return "P000000001";
  }
}
