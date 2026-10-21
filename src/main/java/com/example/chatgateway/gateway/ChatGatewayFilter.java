package com.example.chatgateway.gateway;

import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayErrorHandler;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.service.ChatAuthenticationService;
import com.example.chatgateway.session.Credentials;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 중계 요청을 파싱하고 인증 서비스 호출 후 HTTP/SSE 전송 자원을 관리한다. */
@Component
@Order(0)
public class ChatGatewayFilter extends OncePerRequestFilter {
  public static final String SESSION = ChatGatewayFilter.class.getName() + ".session";
  public static final String CONTEXT = ChatGatewayFilter.class.getName() + ".context";
  private final ChatAuthenticationService authentication;
  private final MfaResultParser mfaParser;
  public static final String VERIFIED = ChatGatewayFilter.class.getName() + ".verified";
  private final SessionSettings settings;
  private final ObjectMapper mapper;
  private final GatewayErrorHandler errors;
  private final Semaphore admission;

  /** 인증과 문맥 저장, 원문 중계 및 동시 요청 상한을 주입한다. */
  public ChatGatewayFilter(
      ChatAuthenticationService authentication,
      MfaResultParser mfaParser,
      SessionSettings settings,
      ObjectMapper mapper,
      GatewayErrorHandler errors,
      ChatProperties props) {
    this.authentication = authentication;
    this.mfaParser = mfaParser;
    this.settings = settings;
    this.mapper = mapper;
    this.errors = errors;
    this.admission = new Semaphore(props.backend().maxStreams());
  }

  /** 챗봇 중계 경로에만 적용하고 인증·MFA REST API는 각 컨트롤러가 처리한다. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().equals("/api/chat/auto")
        || !request.getRequestURI().equals("/api/chat")
            && !request.getRequestURI().startsWith("/api/chat/");
  }

  /** 필터 체인이 SSE 전송을 마칠 때까지 admission을 유지한다. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    boolean acquired = false;
    try {
      if (!ChatGatewayRoutes.allowed(request)) throw new GatewayException(404, "NOT_FOUND");
      acquired = admission.tryAcquire();
      if (!acquired || settings.backendCredential().isBlank()) throw GatewayException.unavailable();
      var prepared = prepareRequest(request, response, request.getRequestURI());
      chain.doFilter(
          prepared,
          new HttpServletResponseWrapper(response) {
            @Override
            public ServletOutputStream getOutputStream() throws IOException {
              // 공식 중계가 본문을 읽기 전에 확정된 쿠키와 헤더부터 보낸다.
              flushBuffer();
              return super.getOutputStream();
            }
          });
    } catch (Exception error) {
      try {
        errors.handle(response, error);
      } catch (IOException io) {
        throw io;
      } catch (Exception propagated) {
        throw new ServletException(propagated);
      }
    } finally {
      // 조건부 GET 등 공식 handler가 본문을 생략한 경우도 응답 자원을 반환한다.
      var upstream = (ClientHttpResponse) request.getAttribute(MvcUtils.CLIENT_RESPONSE_ATTR);
      try {
        if (upstream != null) upstream.close();
      } finally {
        if (acquired) admission.release();
      }
    }
  }

  /** 본문과 thread를 검사하고 인증·실행 문맥을 저장한 뒤 중계할 요청을 준비한다. */
  private HttpServletRequest prepareRequest(
      HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
    byte[] body = null;
    String thread =
        path.equals("/api/chat/threads") || path.equals("/api/chat") ? null : path.split("/")[4];
    if (path.equals("/api/chat")) {
      if (request.getContentLengthLong() > 1048576)
        throw new GatewayException(413, "RESOURCE_EXHAUSTED");
      body = request.getInputStream().readNBytes(1048577);
      if (body.length > 1048576) throw new GatewayException(413, "RESOURCE_EXHAUSTED");
      thread = thread(body);
    } else if (thread != null) thread = Credentials.identifier(thread);
    var results = mfaParser.parse(Collections.list(request.getHeaders("X-Mfa-Result")));
    if (!results.isEmpty() && (!path.equals("/api/chat") || !"POST".equals(request.getMethod())))
      throw GatewayException.badRequest();
    var authenticated = authentication.authenticate(request, response, thread, body, results);
    request.setAttribute(SESSION, authenticated.session());
    request.setAttribute(CONTEXT, authenticated.context());
    if (!authenticated.verifiedAuthIds().isEmpty())
      request.setAttribute(VERIFIED, String.join(", ", authenticated.verifiedAuthIds()));
    return proxyRequest(request, body);
  }

  /** 인증에서 읽은 JSON만 다시 제공하고 도구 실행 본문은 버퍼링하지 않는다. */
  private HttpServletRequest proxyRequest(HttpServletRequest request, byte[] body) {
    var input = new ByteArrayInputStream(body == null ? new byte[0] : body);
    var stream =
        new ServletInputStream() {
          @Override
          public boolean isFinished() {
            return input.available() == 0;
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Blocking proxy");
          }

          @Override
          public int read() {
            return input.read();
          }

          @Override
          public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
          }
        };
    return new HttpServletRequestWrapper(request) {
      // Gateway는 원문 query를 직접 전달한다. Servlet의 form 파싱으로 본문이 소비되지 않게 한다.
      @Override
      public Map<String, String[]> getParameterMap() {
        return Map.of();
      }

      @Override
      public ServletInputStream getInputStream() throws IOException {
        return body == null ? super.getInputStream() : stream;
      }
    };
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
