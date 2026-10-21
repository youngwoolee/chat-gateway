package com.example.chatgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chatgateway.client.pfc.MockPfcClient;
import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.session.HttpSessionServiceClient;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.LocalAuthorityStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OfflineConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SessionConfiguration.class,
              SessionServiceClientConfiguration.class,
              ChannelAccessConfiguration.class,
              OfflineConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new);

  @Test
  void offlineUsesOnlyTemporaryImplementations() {
    context
        .withPropertyValues("spring.profiles.active=offline")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(AuthorityStore.class)).isInstanceOf(LocalAuthorityStore.class);
              assertThat(ctx.getBean(PfcClient.class)).isInstanceOf(MockPfcClient.class);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "dev", "prod"})
  void internalProfilesNeverUseOfflineImplementations(String profile) {
    context
        .withPropertyValues("spring.profiles.active=" + profile)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(AuthorityStore.class))
                  .isInstanceOf(HttpSessionServiceClient.class);
              assertThat(ctx.getBean(PfcClient.class)).isNotInstanceOf(MockPfcClient.class);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "dev", "prod"})
  void mixedProfilesFailInsteadOfUsingMocks(String profile) {
    context
        .withPropertyValues("spring.profiles.active=offline," + profile)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseMessage("offline cannot be combined with local/dev/prod");
            });
  }
}
