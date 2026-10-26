package com.example.chatgateway.service;

import com.example.chatgateway.client.agent.AgentBackendClient;
import com.example.chatgateway.exception.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/** MVC emitter 초기화 후 구독하여 SSE와 일반 응답을 원문 그대로 중계한다. */
@Service
public class SseRelayService implements WebMvcConfigurer, AsyncHandlerInterceptor {
  private static final String TASK = SseRelayService.class.getName() + ".task";
  private final AgentBackendClient backend;

  public SseRelayService(AgentBackendClient backend) {
    this.backend = backend;
  }

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
      task.run();
    }
  }

  public ResponseBodyEmitter prepare(
      HttpServletRequest request,
      HttpServletResponse response,
      WebClient.RequestHeadersSpec<?> upstream,
      Runnable release) {
    var emitter = new ResponseBodyEmitter(0L);
    var subscriber =
        new BaseSubscriber<DataBuffer>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            request(1);
          }

          @Override
          protected void hookOnNext(DataBuffer buffer) {
            try {
              byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              emitter.send(bytes, MediaType.APPLICATION_OCTET_STREAM);
            } catch (Exception error) {
              cancel();
              emitter.completeWithError(error);
              return;
            } finally {
              DataBufferUtils.release(buffer);
            }
            request(1);
          }

          @Override
          protected void hookOnComplete() {
            emitter.complete();
          }

          @Override
          protected void hookOnError(Throwable error) {
            emitter.completeWithError(
                response.isCommitted() ? error : GatewayException.unavailable());
          }

          @Override
          protected void hookFinally(SignalType type) {
            release.run();
          }
        };
    emitter.onCompletion(subscriber::cancel);
    emitter.onTimeout(subscriber::cancel);
    emitter.onError(error -> subscriber.cancel());
    request.setAttribute(
        TASK,
        (Runnable)
            () ->
                backend
                    .exchange(
                        upstream,
                        received ->
                            Mono.fromCallable(
                                    () -> {
                                      response.setStatus(received.status());
                                      received
                                          .headers()
                                          .forEach(
                                              (name, values) ->
                                                  values.forEach(
                                                      value -> response.addHeader(name, value)));
                                      boolean notModified =
                                          "GET".equals(request.getMethod())
                                              && received.status() == 200
                                              && new ServletWebRequest(request, response)
                                                  .checkNotModified(
                                                      received.headers().getETag(),
                                                      received.headers().getLastModified());
                                      // 첫 데이터가 늦어도 인증 쿠키와 B/E 상태를 즉시 전달한다.
                                      response.flushBuffer();
                                      return !notModified
                                          && received.status() != 204
                                          && received.status() != 304;
                                    })
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(hasBody -> hasBody ? received.body() : Flux.empty()))
                    // Servlet 쓰기는 blocking이므로 Netty event loop에서 분리하고 미리 읽기를 제한한다.
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                    .subscribe(subscriber));
    return emitter;
  }
}
