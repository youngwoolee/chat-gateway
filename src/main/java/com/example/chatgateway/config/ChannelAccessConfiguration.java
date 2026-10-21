package com.example.chatgateway.config;

import com.example.chatgateway.client.access.ChannelAccessRepository;
import com.example.chatgateway.exception.GatewayException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Mongo 호환 Document DB와 로컬 테스트 목록을 분리한다. */
@Configuration
@EnableConfigurationProperties(StagingProperties.class)
public class ChannelAccessConfiguration {
  /** 로컬 테스트에서는 DB 없이 설정의 합성 고객번호 목록만 조회한다. */
  @Bean
  @Profile("local")
  ChannelAccessRepository localChannelAccess(StagingProperties settings) {
    return (channel, customer) ->
        settings.localAllowedCustomers().getOrDefault(channel, Set.of()).contains(customer);
  }

  @Configuration
  @Profile("!local")
  @ConditionalOnProperty(prefix = "chat.staging", name = "mongo-uri")
  static class DocumentDb {
    /** 연결·조회 대기시간을 제한하며 애플리케이션 종료 시 연결 풀을 닫는다. */
    @Bean(destroyMethod = "close")
    MongoClient channelAccessMongoClient(StagingProperties settings) {
      return MongoClients.create(
          MongoClientSettings.builder()
              .applyConnectionString(new ConnectionString(settings.mongoUri()))
              .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
              .applyToSocketSettings(
                  b -> b.connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS))
              .applyToConnectionPoolSettings(b -> b.maxSize(10).maxWaitTime(3, TimeUnit.SECONDS))
              .build());
    }

    /** DB의 불리언 enabled=true인 정확한 채널·고객 조합만 허용한다. */
    @Bean
    ChannelAccessRepository documentChannelAccess(MongoClient client, StagingProperties settings) {
      var collection =
          client.getDatabase(settings.database()).getCollection("channel_customer_access");
      return (channel, customer) ->
          collection
                  .find(
                      new Document("channelId", channel)
                          .append("customerId", customer)
                          .append("enabled", true))
                  .projection(new Document("_id", 1))
                  .first()
              != null;
    }
  }

  /** DB 미설정 상태에서는 staging 권한을 임의로 허용하지 않는다. */
  @Bean
  @Profile("!local")
  @ConditionalOnMissingBean(ChannelAccessRepository.class)
  ChannelAccessRepository unavailableChannelAccess() {
    return (channel, customer) -> {
      throw GatewayException.unavailable();
    };
  }
}
