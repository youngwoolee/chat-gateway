package com.example.chatgateway.service;

import com.example.chatgateway.client.agent.AgentBackendClient;
import com.example.chatgateway.client.agent.BackendHeaders;
import com.example.chatgateway.client.pfc.PfcClient.MfaInput;
import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.gateway.MfaResultParser;
import com.example.chatgateway.service.ChatAuthenticationService.AuthenticatedChat;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** 입력 확인 → 인증 → B/E 호출 → REST 또는 SSE 전송 순서로 처리한다. */
@Service
public class ChatService {
  private final ChatAuthenticationService authentication;
  private final MfaResultParser mfaParser;
  private final AgentBackendClient backend;
  private final BackendHeaders headers;
  private final SseRelayService streams;
  private final Semaphore admission;

  public ChatService(
      ChatAuthenticationService authentication,
      MfaResultParser mfaParser,
      AgentBackendClient backend,
      BackendHeaders headers,
      SseRelayService streams,
      ChatProperties properties) {
    this.authentication = authentication;
    this.mfaParser = mfaParser;
    this.backend = backend;
    this.headers = headers;
    this.streams = streams;
    admission = new Semaphore(properties.backend().maxStreams());
  }

  /** 요청 크기를 제한하고 인증한 뒤 발화 본문을 원문 중계한다. */
  public ResponseBodyEmitter chat(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    byte[] body = readChatBody(request);
    var results = mfaParser.parse(Collections.list(request.getHeaders("X-Mfa-Result")));
    return send(
        request,
        response,
        body,
        results,
        authenticated ->
            backend.chat(
                target(request, authenticated), headers.request(request, authenticated), body));
  }

  /** 도구 요청의 경로와 본문을 원문 그대로 전달한다. */
  public ResponseBodyEmitter tool(
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    rejectMfa(request);
    var body = request.getInputStream();
    return send(
        request,
        response,
        null,
        null,
        authenticated ->
            backend.executeTool(
                target(request, authenticated),
                headers.request(request, authenticated),
                body,
                request.getContentLengthLong()));
  }

  /** 조회는 세션을 연장하지 않고 응답을 비동기로 중계한다. */
  public ResponseBodyEmitter history(
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    rejectMfa(request);
    return send(
        request,
        response,
        null,
        null,
        authenticated ->
            backend.history(
                target(request, authenticated), headers.request(request, authenticated)));
  }

  private ResponseBodyEmitter send(
      HttpServletRequest request,
      HttpServletResponse response,
      byte[] body,
      MfaInput results,
      Function<AuthenticatedChat, WebClient.RequestHeadersSpec<?>> call)
      throws Exception {
    acquire(request);
    try {
      var authenticated = authentication.authenticate(request, response, body, results);
      return streams.prepare(request, response, call.apply(authenticated), admission::release);
    } catch (Exception error) {
      admission.release();
      throw error;
    }
  }

  /** 공개 컨트롤러가 매핑한 경로만 B/E 경로로 변환한다. */
  private URI target(HttpServletRequest request, AuthenticatedChat authenticated) {
    return backend.target(
        authenticated.session().channel(),
        request.getRequestURI().substring("/api/chat".length()),
        request.getQueryString());
  }

  /** 초과 요청은 B/E 연결을 만들기 전에 거절한다. */
  private void acquire(HttpServletRequest request) {
    if (request.getDispatcherType() != DispatcherType.REQUEST)
      throw new GatewayException(404, "NOT_FOUND");
    if (!admission.tryAcquire()) throw GatewayException.unavailable();
  }

  /** 발화 JSON에만 크기 제한을 적용한다. */
  private byte[] readChatBody(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > 1048576) throw GatewayException.badRequest();
    byte[] body = request.getInputStream().readNBytes(1048577);
    if (body.length > 1048576) throw GatewayException.badRequest();
    return body;
  }

  private void rejectMfa(HttpServletRequest request) {
    if (mfaParser.parse(Collections.list(request.getHeaders("X-Mfa-Result"))) != null)
      throw GatewayException.badRequest();
  }

}
