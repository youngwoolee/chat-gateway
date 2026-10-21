package com.example.chatgateway;

import com.example.chatgateway.config.ChannelProperties;
import com.example.chatgateway.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ChatProperties.class, ChannelProperties.class})
public class ChatGatewayApplication {
  /** Spring Boot를 시작하여 Gateway 라우트와 프로필별 인증·저장소 연동을 구성한다. */
  public static void main(String[] args) {
    SpringApplication.run(ChatGatewayApplication.class, args);
  }
}
