package com.example.chatgateway.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SessionSettings.class, MfaProperties.class})
public class SessionConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
