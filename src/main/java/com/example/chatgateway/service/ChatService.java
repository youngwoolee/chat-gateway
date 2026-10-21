package com.example.chatgateway.service;

import com.example.chatgateway.client.agent.AgentBackendClient;
import com.example.chatgateway.client.agent.BackendHeaders;
import com.example.chatgateway.client.pfc.PfcClient.MfaResult;
import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.gateway.MfaResultParser;
import com.example.chatgateway.service.ChatAuthenticationService.AuthenticatedChat;
import com.example.chatgateway.session.Credentials;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 입력 확인 → 인증 → B/E 호출 → REST 또는 SSE 전송 순서로 처리한다. */
@Service
public class ChatService {
  private final ChatAuthenticationService authentication;
  private final MfaResultParser mfaParser;
  private final AgentBackendClient backend;
  private final BackendHeaders headers;
  private final SseRelayService streams;
  private final ObjectMapper mapper;
  private final SessionSettings settings;
  private final Semaphore admission;

  public ChatService(
      ChatAuthenticationService authentication,
      MfaResultParser mfaParser,
      AgentBackendClient backend,
      BackendHeaders headers,
      SseRelayService streams,
      ObjectMapper mapper,
      SessionSettings settings,
      ChatProperties properties) {
    this.authentication = authentication;
    this.mfaParser = mfaParser;
    this.backend = backend;
    this.headers = headers;
    this.streams = streams;
    this.mapper = mapper;
    this.settings = settings;
    admission = new Semaphore(properties.backend().maxStreams());
  }

  /** 일반 JSON 발화를 검증하고 인증한 뒤 B/E 응답을 전달한다. */
  public SseEmitter chat(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    byte[] body = readChatBody(request);
    var results = mfaParser.parse(Collections.list(request.getHeaders("X-Mfa-Result")));
    return send(
        request,
        response,
        thread(body),
        body,
        results,
        authenticated ->
            backend.chat(
                target(request, authenticated), headers.request(request, authenticated), body));
  }

  /** 도구 요청은 경로의 대화 ID를 검증하고 원래 본문 그대로 전달한다. */
  public SseEmitter tool(HttpServletRequest request, HttpServletResponse response, String threadId)
      throws Exception {
    rejectMfa(request);
    var body = request.getInputStream();
    return send(
        request,
        response,
        Credentials.identifier(threadId),
        null,
        List.of(),
        authenticated ->
            backend.executeTool(
                target(request, authenticated),
                headers.request(request, authenticated),
                body,
                request.getContentLengthLong()));
  }

  /** 조회는 세션을 연장하지 않으며 일반 HTTP 응답으로 처리한다. */
  public void history(HttpServletRequest request, HttpServletResponse response, String threadId)
      throws Exception {
    acquire(request);
    try {
      rejectMfa(request);
      var authenticated =
          authentication.authenticate(
              request,
              response,
              threadId == null ? null : Credentials.identifier(threadId),
              null,
              List.of());
      try (var upstream =
          backend.history(
              target(request, authenticated), headers.request(request, authenticated))) {
        copyHeaders(response, upstream);
        if (upstream.status() != 200
            || !new ServletWebRequest(request, response)
                .checkNotModified(
                    upstream.headers().getETag(), upstream.headers().getLastModified())) {
          writeBody(response, upstream);
        }
      }
    } finally {
      admission.release();
    }
  }

  /** SSE 연결만 완료 콜백에 넘긴다. 일반 응답과 실패는 현재 요청에서 정리한다. */
  private SseEmitter send(
      HttpServletRequest request,
      HttpServletResponse response,
      String threadId,
      byte[] body,
      List<MfaResult> results,
      Function<AuthenticatedChat, AgentBackendClient.Response> call)
      throws Exception {
    acquire(request);
    AgentBackendClient.Response upstream = null;
    boolean streaming = false;
    try {
      var authenticated = authentication.authenticate(request, response, threadId, body, results);
      upstream = call.apply(authenticated);
      copyHeaders(response, upstream);
      MediaType type = upstream.headers().getContentType();
      if (hasBody(upstream) && type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
        SseEmitter emitter = streams.prepare(request, response, upstream, admission::release);
        streaming = true;
        return emitter;
      }
      writeBody(response, upstream);
      return null;
    } finally {
      if (!streaming) {
        try {
          if (upstream != null) upstream.close();
        } finally {
          admission.release();
        }
      }
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
    if (settings.backendCredential().isBlank() || !admission.tryAcquire())
      throw GatewayException.unavailable();
  }

  /** 발화 JSON에만 크기 제한을 적용한다. */
  private byte[] readChatBody(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > 1048576) throw GatewayException.badRequest();
    byte[] body = request.getInputStream().readNBytes(1048577);
    if (body.length > 1048576) throw GatewayException.badRequest();
    return body;
  }

  private void rejectMfa(HttpServletRequest request) {
    if (!mfaParser.parse(Collections.list(request.getHeaders("X-Mfa-Result"))).isEmpty())
      throw GatewayException.badRequest();
  }

  private void copyHeaders(HttpServletResponse response, AgentBackendClient.Response upstream)
      throws IOException {
    response.setStatus(upstream.status());
    upstream
        .headers()
        .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
  }

  private boolean hasBody(AgentBackendClient.Response upstream) throws IOException {
    return upstream.status() != 204 && upstream.status() != 304;
  }

  private void writeBody(HttpServletResponse response, AgentBackendClient.Response upstream)
      throws IOException {
    response.flushBuffer();
    if (!hasBody(upstream)) return;
    // 조회 응답도 전체를 기다리지 않고 전달한다 (큰 아티팩트 포함).
    var input = upstream.body();
    var output = response.getOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
      output.flush();
    }
  }

  /** JSON은 threadId 검증에만 사용하고 전송 본문을 재직렬화하지 않는다. */
  private String thread(byte[] body) {
    try {
      var json =
          mapper
              .reader()
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(body);
      if (json == null || !json.isObject() || !json.path("threadId").isTextual())
        throw GatewayException.badRequest();
      return Credentials.identifier(json.path("threadId").textValue());
    } catch (Exception error) {
      throw GatewayException.badRequest();
    }
  }
}
