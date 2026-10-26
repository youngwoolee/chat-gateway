package com.example.chatgateway.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class UpstreamClients {
  @Bean(destroyMethod = "dispose")
  ConnectionProvider proxyConnections(ChatProperties props) {
    return ConnectionProvider.builder("agent-backend")
        .maxConnections(props.backend().maxStreams())
        .pendingAcquireTimeout(Duration.ofMillis(props.backend().connectTimeoutMillis()))
        .build();
  }

  /** 원문 중계를 위해 자동 재시도·리다이렉트·압축 해제를 사용하지 않는다. */
  @Bean
  WebClient proxyClient(ChatProperties props, ConnectionProvider proxyConnections) {
    var client =
        HttpClient.create(proxyConnections)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.backend().connectTimeoutMillis())
            .responseTimeout(props.backend().responseTimeout())
            .disableRetry(true)
            .followRedirect(false)
            .compress(false);
    return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client)).build();
  }
}
