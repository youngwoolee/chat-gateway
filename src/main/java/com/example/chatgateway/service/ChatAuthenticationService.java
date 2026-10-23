package com.example.chatgateway.service;

import com.example.chatgateway.client.pfc.PfcClient.MfaInput;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

@Service
public class ChatAuthenticationService {
  private final SessionAuthentication auth;
  private final MfaService mfa;

  public ChatAuthenticationService(SessionAuthentication auth, MfaService mfa) {
    this.auth = auth;
    this.mfa = mfa;
  }

  public AuthenticatedChat authenticate(
      HttpServletRequest request, HttpServletResponse response, byte[] body, MfaInput input) {
    Session session =
        "POST".equals(request.getMethod())
            ? auth.authenticateActivity(request, response)
            : auth.inspect(request, response);
    String verified = input == null ? null : mfa.verifyResume(session, request, body, input);
    return new AuthenticatedChat(session, verified);
  }

  public record AuthenticatedChat(Session session, String verifiedRequestId) {}
}
