package com.example.chatgateway.config;

import com.example.chatgateway.cookie.SessionCookies;
import java.util.HashSet;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 채널별 인증 유형을 서버 허용 목록으로 관리한다.
 *
 * @param allowed 채널 ID → 인증 유형 매핑. 비어 있으면 모든 채널 요청을 거절한다.
 */
@ConfigurationProperties("chat.channels")
public record ChannelProperties(Map<String, AuthType> allowed) {
  public ChannelProperties {
    var names = new HashSet<String>();
    if (allowed != null)
      for (String channel : allowed.keySet())
        if (!names.add(SessionCookies.name(channel)))
          throw new IllegalArgumentException("Channel cookie prefixes must be unique");
  }

  /** 서버 허용 목록에 등록된 채널만 반환한다. 미등록 채널은 403이며 지원하지 않는 인증 유형 설정은 오류로 처리한다. */
  public AuthType require(String channelId) {
    if (channelId == null || allowed == null || !allowed.containsKey(channelId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Channel is not allowed");
    }
    AuthType type = allowed.get(channelId);
    if (type != AuthType.PFC) {
      throw new IllegalStateException("Channel authentication type must be PFC");
    }
    return type;
  }
}
