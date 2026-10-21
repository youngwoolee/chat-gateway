package com.example.chatgateway.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AutocompleteConfiguration {
  /** Eureka 서비스 ID를 해석하며 SSE와 별도로 연결·응답 대기 시간을 제한한다. */
  @Bean
  @LoadBalanced
  RestClient.Builder autocompleteRestClientBuilder() {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().requestFactory(factory);
  }

  /** 고정된 서비스 ID만 호출하고 브라우저의 인증 헤더·쿠키를 전달하지 않는다. */
  @Bean
  RestClient autocompleteRestClient(
      @Qualifier("autocompleteRestClientBuilder") RestClient.Builder builder) {
    return builder.baseUrl("http://search-autocomplete-service").build();
  }
}
