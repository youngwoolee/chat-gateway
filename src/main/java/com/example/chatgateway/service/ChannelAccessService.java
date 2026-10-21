package com.example.chatgateway.service;

import com.example.chatgateway.client.access.ChannelAccessRepository;
import com.example.chatgateway.config.StagingProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Session;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

/** staging 고객 접근 권한을 제한된 메모리 캐시로 확인한다. */
@Service
public class ChannelAccessService {
  private final StagingProperties settings;
  private final ChannelAccessRepository repository;
  private final Cache<Customer, Boolean> cache;

  public ChannelAccessService(StagingProperties settings, ChannelAccessRepository repository) {
    this.settings = settings;
    this.repository = repository;
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(settings.cacheSize())
            .expireAfterWrite(settings.cacheTtl())
            .build();
  }

  /** 세션에서 확정된 신원으로 조회하며 production은 기존 인증 정책을 유지한다. */
  public void require(Session session) {
    require(session.channel(), session.subject(), session.member());
  }

  /** 게스트는 DB 조회 없이 차단하고, DB 오류는 캐싱하지 않고 503으로 반환한다. */
  public void require(String channel, String customerId, boolean member) {
    if (!settings.channels().containsKey(channel)) return;
    if (!member) throw GatewayException.denied();
    boolean allowed;
    try {
      allowed =
          cache.get(
              new Customer(channel, customerId),
              key -> repository.isAllowed(key.channel(), key.customerId()));
    } catch (RuntimeException failure) {
      throw GatewayException.unavailable();
    }
    if (!allowed) throw GatewayException.denied();
  }

  private record Customer(String channel, String customerId) {}
}
