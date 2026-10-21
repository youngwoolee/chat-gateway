package com.example.chatgateway.service;

import com.example.chatgateway.client.pfc.PfcClient.MfaResult;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** 중계 전에 세션 인증·MFA 검증·인증 문맥 생성을 완료한다. 전송 연결은 호출 서비스가 관리한다. */
@Service
public class ChatAuthenticationService {
  private final SessionAuthentication auth;
  private final MfaService mfa;
  private final AuthorityStore store;
  private final SessionSettings settings;
  private final Clock clock;

  public ChatAuthenticationService(
      SessionAuthentication auth,
      MfaService mfa,
      AuthorityStore store,
      SessionSettings settings,
      Clock clock) {
    this.auth = auth;
    this.mfa = mfa;
    this.store = store;
    this.settings = settings;
    this.clock = clock;
  }

  /** 기존 세션을 인증하고 필요한 MFA를 검증한 뒤 중계에 사용할 신뢰 정보를 반환한다. */
  public AuthenticatedChat authenticate(
      HttpServletRequest request,
      HttpServletResponse response,
      String thread,
      byte[] body,
      List<MfaResult> results) {
    var session =
        "POST".equals(request.getMethod())
            ? auth.authenticateActivity(request, response, thread)
            : auth.inspect(request, response);
    // 인증 등록은 회원·대화 문맥이 필요하므로 사용 불가능한 자격증명을 저장하지 않는다.
    String context =
        session.member() && thread != null ? saveExecutionGrant(session, thread) : null;
    List<String> verified = List.of();
    if (!results.isEmpty()) {
      String target =
          request.getRequestURI()
              + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
      verified =
          mfa.verifyResume(
              session, thread, SessionAuthentication.key(request), target, body, results);
    }
    return new AuthenticatedChat(session, context, verified);
  }

  /** 실행 문맥 저장이 성공한 경우에만 upstream 호출을 허용한다. */
  private String saveExecutionGrant(Session session, String thread) {
    String context = Credentials.random();
    if (store.saveGrant(
            new AuthorityStore.ExecutionGrant(
                Credentials.hash(context),
                session.subject(),
                session.userType(),
                session.channel(),
                thread,
                clock.millis() + settings.executionTimeout().toMillis()))
        == null) throw GatewayException.badGateway();
    return context;
  }

  public record AuthenticatedChat(Session session, String context, List<String> verifiedAuthIds) {}
}
