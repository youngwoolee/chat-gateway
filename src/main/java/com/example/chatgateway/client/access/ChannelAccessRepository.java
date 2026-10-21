package com.example.chatgateway.client.access;

/** 채널·고객번호에 대한 활성 허용 목록 조회. 장애를 허용 결과로 대체하지 않는다. */
public interface ChannelAccessRepository {
  boolean isAllowed(String channelId, String customerId);
}
