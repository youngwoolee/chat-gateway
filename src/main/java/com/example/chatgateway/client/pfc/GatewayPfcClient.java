package com.example.chatgateway.client.pfc;

import com.example.chatgateway.exception.GatewayException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 세션 인증 흐름을 기존 PFC 인증·쿠키 생성 서비스에 연결한다. */
@Component
@Profile("!offline")
public class GatewayPfcClient implements PfcClient {
  private final PfcAuthService pfcAuthService;
  private final CookieService cookieService;

  public GatewayPfcClient(PfcAuthService pfcAuthService, CookieService cookieService) {
    this.pfcAuthService = pfcAuthService;
    this.cookieService = cookieService;
  }

  @Override
  public String getUserId(IdentityCookies cookies) {
    return pfcAuthService.getUserId(
        cookies.systemCode(), "CBA", cookies.authentication(), cookies.verify());
  }

  @Override
  public IdentityCookies createLoginCookie(String subject) {
    var result = cookieService.createLoginCookie(subject);
    if (result == null) throw GatewayException.badGateway();
    return new IdentityCookies(
        result.platformSystemCode(), result.platformAuthentication(), result.platformVerify());
  }

  @Override
  public String verifyTicketId(MfaInput input, String state) {
    // 기존 VerifyTicketResult에는 회원 PID가 없어 검증 대상과 세션 소유자를 대조할 수 없다.
    throw GatewayException.unavailable();
  }
}
