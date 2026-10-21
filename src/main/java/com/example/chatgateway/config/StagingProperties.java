package com.example.chatgateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** staging 채널과 서버 목적지, 권한 저장소 설정. 프론트는 목적지 주소를 지정하지 못한다. */
@ConfigurationProperties("chat.staging")
public record StagingProperties(
    @DefaultValue Map<String, String> channels,
    @DefaultValue("1m") Duration cacheTtl,
    @DefaultValue("10000") int cacheSize,
    @DefaultValue("") String mongoUri,
    @DefaultValue("chat_gateway") String database) {
  public StagingProperties {
    channels = channels == null ? Map.of() : Map.copyOf(channels);
    if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheSize < 1)
      throw new IllegalArgumentException("Staging cache limits must be positive");
    channels
        .values()
        .forEach(
            value -> {
              URI uri = URI.create(value);
              if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                  || uri.getHost() == null
                  || uri.getUserInfo() != null
                  || uri.getQuery() != null
                  || uri.getFragment() != null)
                throw new IllegalArgumentException(
                    "Staging destination must be a server HTTP(S) URL");
            });
  }
}
