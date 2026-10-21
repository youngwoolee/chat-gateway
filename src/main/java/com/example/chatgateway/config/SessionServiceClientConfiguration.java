package com.example.chatgateway.config;

import com.example.chatgateway.client.session.HttpSessionServiceClient;
import com.example.chatgateway.session.AuthorityStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("!local")
@EnableConfigurationProperties(SessionServiceProperties.class)
public class SessionServiceClientConfiguration {
  /** SSE 풀과 분리하고 재시도·redirect·쿠키 공유를 금지한다. */
  @Bean
  CloseableHttpClient sessionServiceHttpClient(SessionServiceProperties properties) {
    var connect = Timeout.ofMilliseconds(properties.connectTimeout().toMillis());
    var response = Timeout.ofMilliseconds(properties.responseTimeout().toMillis());
    var pool =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(50)
            .setMaxConnPerRoute(50)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(connect)
                    .setSocketTimeout(response)
                    .build())
            .build();
    return HttpClients.custom()
        .setConnectionManager(pool)
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectionRequestTimeout(connect)
                .setResponseTimeout(response)
                .build())
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .disableCookieManagement()
        .build();
  }

  /** Eureka에서 얻은 서비스 인스턴스로 논리 호스트 이름을 해석한다. */
  @Bean
  @LoadBalanced
  RestClient.Builder sessionServiceRestClientBuilder(
      @Qualifier("sessionServiceHttpClient") CloseableHttpClient client) {
    return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(client));
  }

  /** 운영 상태 처리는 외부 서비스로 위임하며 메모리 fallback을 두지 않는다. */
  @Bean
  @ConditionalOnMissingBean(AuthorityStore.class)
  AuthorityStore remoteAuthority(
      @Qualifier("sessionServiceRestClientBuilder") RestClient.Builder builder,
      SessionServiceProperties properties,
      ObjectMapper mapper) {
    return new HttpSessionServiceClient(
        builder.baseUrl("http://" + properties.serviceId()).build(), properties, mapper);
  }
}
