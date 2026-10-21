package com.example.chatgateway.service;

import com.example.chatgateway.client.agent.AgentBackendClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SseEmitter 초기화 후에만 B/E 읽기를 시작하여 느린 FE에도 메모리 버퍼가 쌓이지 않게 한다. */
@Service
public class SseRelayService implements WebMvcConfigurer, AsyncHandlerInterceptor {
  private static final String TASK = SseRelayService.class.getName() + ".task";

  /** MVC가 emitter를 준비한 뒤 가상 스레드에서 한 연결을 순차 중계한다. */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  @Override
  public void afterConcurrentHandlingStarted(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Runnable task = (Runnable) request.getAttribute(TASK);
    if (task != null) {
      request.removeAttribute(TASK);
      Thread.startVirtualThread(task);
    }
  }

  /** 완료·브라우저 단절·타임아웃 모두 B/E 연결과 동시 요청 슬롯을 한 번만 반환한다. */
  public SseEmitter prepare(
      HttpServletRequest request,
      HttpServletResponse response,
      AgentBackendClient.Response upstream,
      Runnable release) {
    var emitter = new SseEmitter(0L);
    var closed = new AtomicBoolean();
    Runnable close =
        () -> {
          if (!closed.compareAndSet(false, true)) return;
          try {
            upstream.close();
          } finally {
            release.run();
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    request.setAttribute(
        TASK,
        (Runnable)
            () -> {
              try {
                // 첫 B/E 데이터가 늦어도 인증 쿠키와 상태는 즉시 전달한다.
                response.flushBuffer();
                byte[] buffer = new byte[8192];
                int count;
                var input = upstream.body();
                while (!closed.get() && (count = input.read(buffer)) != -1) {
                  // 이미 완성된 SSE 바이트를 전송한다. send(Object)는 data:를 다시 붙이므로 사용하지 않는다.
                  emitter.send(
                      Set.of(
                          new DataWithMediaType(
                              Arrays.copyOf(buffer, count), MediaType.APPLICATION_OCTET_STREAM)));
                }
                emitter.complete();
              } catch (Exception error) {
                emitter.completeWithError(error);
              } finally {
                close.run();
              }
            });
    return emitter;
  }
}
