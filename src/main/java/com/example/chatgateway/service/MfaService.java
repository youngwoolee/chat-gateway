package com.example.chatgateway.service;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.pfc.PfcClient.MfaInput;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class MfaService {
  private final PfcClient platform;
  private final SessionCookies cookies;
  private final ObjectMapper mapper;

  public MfaService(PfcClient platform, SessionCookies cookies, ObjectMapper mapper) {
    this.platform = platform;
    this.cookies = cookies;
    this.mapper = mapper;
  }

  /** 본문은 이미 엄격 JSON 검증을 통과했다. 인증 결과는 단일 resolved interrupt에만 결합한다. */
  public String verifyResume(
      Session session, HttpServletRequest request, byte[] body, MfaInput input) {
    JsonNode resume;
    try {
      resume = mapper.readTree(body).path("resume");
    } catch (Exception invalid) {
      throw GatewayException.badRequest();
    }
    if (!resume.isArray()
        || resume.size() != 1
        || !"resolved".equals(resume.get(0).path("status").asText())
        || !resume.get(0).path("interruptId").isTextual()) throw GatewayException.badRequest();
    String id = resume.get(0).path("interruptId").textValue();
    if (id.isBlank() || id.length() > 4096 || !id.matches("[!-~]+"))
      throw GatewayException.badRequest();
    String state = cookies.mfaState(request);
    if (!session.member()) throw GatewayException.mfaInvalid();
    String subject;
    try {
      subject = platform.verify(input, state);
    } catch (GatewayException error) {
      if (error.code() == 401 || error.code() == 403) throw GatewayException.mfaInvalid();
      throw GatewayException.unavailable();
    } catch (RuntimeException error) {
      throw GatewayException.unavailable();
    }
    if (subject == null || !subject.matches("P[0-9]{9}")) throw GatewayException.unavailable();
    if (!session.subject().equals(subject)) throw GatewayException.mfaInvalid();
    return id;
  }
}
