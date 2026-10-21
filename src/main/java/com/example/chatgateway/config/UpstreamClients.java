package com.example.chatgateway.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpstreamClients {
  /** 장기 HTTP/SSE 전용 풀을 만들고 자동 재시도·리다이렉트·쿠키 저장·압축 해제를 끈다. */
  @Bean
  CloseableHttpClient proxyClient(ChatProperties props) {
    if (props.backend().maxStreams() < 1)
      throw new IllegalArgumentException("max-streams must be positive");
    int capacity = props.backend().maxStreams();
    var timeout = Timeout.ofMilliseconds(props.backend().responseTimeout().toMillis());
    var pool =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(capacity)
            .setMaxConnPerRoute(capacity)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(
                        Timeout.ofMilliseconds(props.backend().connectTimeoutMillis()))
                    // HC5 zero validates every reuse; negative disables validation.
                    .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                    .setSocketTimeout(timeout)
                    .build())
            .build();
    var builder =
        HttpClients.custom()
            .setConnectionManager(pool)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(timeout)
                    .setConnectionRequestTimeout(
                        Timeout.ofMilliseconds(props.backend().connectTimeoutMillis()))
                    .build())
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableContentCompression();
    builder.addExecInterceptorFirst("abort-unread-response", abortUnreadResponse());
    return builder.build();
  }

  /** 브라우저 단절 시 close가 남은 무한 SSE를 읽어 버리지 않도록 미소비 응답을 취소한다. */
  private ExecChainHandler abortUnreadResponse() {
    return (request, scope, chain) -> {
      var response = chain.proceed(request, scope);
      if (response.getEntity() == null) return response;
      var entity = response.getEntity();
      var input =
          new FilterInputStream(entity.getContent()) {
            private boolean ended;

            @Override
            public int read() throws IOException {
              int value = super.read();
              ended = value == -1;
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              int count = super.read(bytes, offset, length);
              ended = count == -1;
              return count;
            }

            @Override
            public void close() throws IOException {
              if (!ended && scope.originalRequest instanceof Cancellable cancellable)
                cancellable.cancel();
              super.close();
            }
          };
      response.setEntity(
          new HttpEntityWrapper(entity) {
            @Override
            public InputStream getContent() {
              return input;
            }

            @Override
            public void close() throws IOException {
              input.close();
            }
          });
      return response;
    };
  }
}
