package com.example.chatgateway.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchange;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
public class GatewayHttpClient {
  /** Gateway의 고정 B/E 주소에는 세션 서비스용 LoadBalancer를 적용하지 않는다. */
  @Bean
  @Primary
  RestClient.Builder gatewayRestClientBuilder(RestClientBuilderConfigurer configurer) {
    return configurer.configure(RestClient.builder());
  }

  /** 공식 Gateway RestClient에 재시도 없는 프록시 전용 연결 풀을 연결한다. */
  @Bean
  ClientHttpRequestFactory gatewayRequestFactory(
      @Qualifier("proxyClient") CloseableHttpClient client) {
    return new HttpComponentsClientHttpRequestFactory(client);
  }

  /** 본문·응답 전송은 공식 구현을 사용하고 원문 query만 복원한다. */
  @Bean
  ProxyExchange gatewayExchange(RestClient.Builder builder, GatewayMvcProperties properties) {
    var delegate = new RestClientProxyExchange(builder.build(), properties);
    return request -> {
      var target =
          UriComponentsBuilder.fromUri(request.getUri())
              .replaceQuery(request.getServerRequest().servletRequest().getQueryString())
              .build(true)
              .toUri();
      var outgoing =
          delegate.request(request.getServerRequest()).uri(target).headers(request.getHeaders());
      request.getResponseConsumers().forEach(outgoing::responseConsumer);
      return delegate.exchange(outgoing.build());
    };
  }
}
