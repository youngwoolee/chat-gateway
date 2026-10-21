package com.example.chatgateway.client.pfc;

import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthRequest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Explicit local fixture. Registered only with the offline profile. */
public final class MockPfcClient implements PfcClient {
  private final Clock clock;
  private final Map<String, String> consumed = new HashMap<>();

  /** local 인증 도전의 만료를 검사할 시계를 주입한다. */
  public MockPfcClient(Clock clock) {
    this.clock = clock;
  }

  /** DEMO/demo-ticket/demo-proof만 local-member로 인증한다. 티켓을 처음 사용한 키를 기억해 다른 요청 키의 재사용을 거절한다. */
  public String authenticate(IdentityCookies c, String key) {

    synchronized (consumed) {
      if (!c.systemCode().equals("DEMO")
          || !c.authentication().equals("demo-ticket")
          || !c.verify().equals("demo-proof")) throw GatewayException.unauthorized();
      String prior = consumed.putIfAbsent(c.authentication(), key);
      if (prior != null && !prior.equals(key)) throw GatewayException.unauthorized();
      return "local-member";
    }
  }

  /** local 테스트용 _I 값을 반환한다. 실제 홈페이지의 인증 자격증명으로 사용할 수 없다. */
  public IdentityCookies homepage(String subject, String key) {
    return new IdentityCookies("CBA", "local-homepage-ticket", "local-proof");
  }

  /** local 전용 SDK 입력. 실제 주민번호 암호화가 아니며 민감값을 포함하지 않는다. */
  public String start(AuthRequest request) {
    return "local:" + request.authReqId();
  }

  /** 플랫폼을 흉내 낸 비소비 검증. 실제 PFC에서는 수단별로 별도 검증해야 한다. */
  public VerifiedTx verify(MfaResult result, AuthRequest request) {
    if (!result.tranID().equals("local:" + request.authReqId()))
      throw GatewayException.mfaInvalid();
    if ("FINANCE_CA".equals(result.method()) && !"local-tx".equals(result.txId()))
      throw GatewayException.mfaInvalid();
    if (!Set.of("FINANCE_CA", "SHINHAN").contains(result.method()))
      throw GatewayException.mfaInvalid();
    if (request.expiresAt() <= clock.millis()) throw GatewayException.mfaExpired();
    return new VerifiedTx(
        request.subject(),
        request.authReqId(),
        result.tranID(),
        result.txId(),
        result.method(),
        request.comparison(),
        request.expiresAt());
  }
}
