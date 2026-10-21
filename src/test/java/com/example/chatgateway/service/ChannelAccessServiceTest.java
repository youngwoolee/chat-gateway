package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.chatgateway.client.access.ChannelAccessRepository;
import com.example.chatgateway.config.StagingProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelAccessServiceTest {
  final ChannelAccessRepository repository = mock(ChannelAccessRepository.class);

  ChannelAccessService service(Duration ttl) {
    return new ChannelAccessService(
        new StagingProperties(Map.of("stage", "http://localhost:8002"), ttl, 100, "", "test"),
        repository);
  }

  @Test
  void productionNeverQueriesAndStagingGuestsAreDenied() {
    var service = service(Duration.ofMinutes(1));
    service.require("production", "guest", false);
    assertThatThrownBy(() -> service.require("stage", "guest", false))
        .hasMessage("PERMISSION_DENIED");
    verifyNoInteractions(repository);
  }

  @Test
  void cachesBothResultsAndSeparatesCustomers() {
    var service = service(Duration.ofMinutes(1));
    when(repository.isAllowed("stage", "allowed")).thenReturn(true);
    for (int i = 0; i < 3; i++) {
      service.require("stage", "allowed", true);
      assertThatThrownBy(() -> service.require("stage", "denied", true))
          .hasMessage("PERMISSION_DENIED");
    }
    verify(repository, times(1)).isAllowed("stage", "allowed");
    verify(repository, times(1)).isAllowed("stage", "denied");
  }

  @Test
  void expiredPermissionCannotSurviveDeletionOrDatabaseFailure() {
    var service = service(Duration.ofNanos(1));
    when(repository.isAllowed("stage", "customer"))
        .thenReturn(true, false)
        .thenThrow(new IllegalStateException("private database details"));
    service.require("stage", "customer", true);
    assertThatThrownBy(() -> service.require("stage", "customer", true))
        .hasMessage("PERMISSION_DENIED");
    assertThatThrownBy(() -> service.require("stage", "customer", true)).hasMessage("UNAVAILABLE");
  }

  @Test
  void failuresAreNotCachedAndRecoveryCanSucceed() {
    var service = service(Duration.ofMinutes(1));
    when(repository.isAllowed("stage", "customer"))
        .thenThrow(new IllegalStateException("private details"))
        .thenReturn(true);
    assertThatThrownBy(() -> service.require("stage", "customer", true)).hasMessage("UNAVAILABLE");
    service.require("stage", "customer", true);
    verify(repository, times(2)).isAllowed("stage", "customer");
  }
}
