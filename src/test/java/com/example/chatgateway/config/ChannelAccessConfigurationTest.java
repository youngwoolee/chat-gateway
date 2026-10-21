package com.example.chatgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.client.access.ChannelAccessRepository;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChannelAccessConfigurationTest {
  final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ChannelAccessConfiguration.class);

  @Test
  void missingDatabaseFailsClosedWithoutConnecting() {
    context.run(
        ctx -> {
          assertThat(ctx)
              .hasSingleBean(ChannelAccessRepository.class)
              .doesNotHaveBean(MongoClient.class);
          assertThatThrownBy(
                  () -> ctx.getBean(ChannelAccessRepository.class).isAllowed("stage", "customer"))
              .hasMessage("UNAVAILABLE");
        });
  }

  @Test
  void configuredDatabaseRegistersOneRepository() {
    context
        .withPropertyValues("chat.staging.mongo-uri=mongodb://127.0.0.1:1/test")
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasSingleBean(ChannelAccessRepository.class)
                  .hasSingleBean(MongoClient.class);
            });
  }
}
