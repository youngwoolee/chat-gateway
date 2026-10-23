package com.example.chatgateway.config;

import com.example.chatgateway.client.access.ChannelAccessRepository;
import com.example.chatgateway.client.pfc.MockPfcClient;
import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.LocalAuthorityStore;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 외부망에서만 사용하는 임시 구현. 기본 배포 JAR에는 포함되지 않는다. */
@Configuration
@Profile("offline")
@EnableConfigurationProperties(OfflineConfiguration.Settings.class)
public class OfflineConfiguration {
  public OfflineConfiguration(Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("local", "dev", "prod")))
      throw new IllegalStateException("offline cannot be combined with local/dev/prod");
  }

  /** 외부 세션 서비스 대신 프로세스 내 메모리를 사용한다. */
  @Bean
  AuthorityStore offlineAuthority() {
    return new LocalAuthorityStore();
  }

  /** 실제 PFC를 호출하지 않고 합성 인증 자료만 처리한다. */
  @Bean
  PfcClient offlinePlatform() {
    return new MockPfcClient();
  }

  /** DB 대신 offline 설정의 고객 목록으로만 권한을 확인한다. */
  @Bean
  ChannelAccessRepository offlineChannelAccess(Settings settings) {
    return (channel, customer) ->
        settings.allowedCustomers().getOrDefault(channel, Set.of()).contains(customer);
  }

  @ConfigurationProperties("chat.offline")
  public record Settings(@DefaultValue Map<String, Set<String>> allowedCustomers) {}
}
