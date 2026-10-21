package com.example.chatgateway.gateway;

import com.example.chatgateway.client.pfc.PfcClient.MfaResult;
import com.example.chatgateway.config.MfaProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Credentials;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** 외부 MFA 헤더를 검증된 입력 목록으로 변환한다. */
@Component
public class MfaResultParser {
  private final MfaProperties policy;
  private final ObjectMapper mapper;

  public MfaResultParser(MfaProperties policy, ObjectMapper mapper) {
    this.policy = policy;
    this.mapper = mapper;
  }

  /** 헤더 전체를 제한된 크기로 엄격 파싱한다. 중복/알 수 없는 필드는 검증 전에 거절한다. */
  public List<MfaResult> parse(List<String> headers) {
    if (headers.isEmpty()) return List.of();
    if (headers.size() != 1) throw GatewayException.badRequest();
    String encoded = headers.getFirst();
    if (encoded.length() > policy.maxHeaderBytes() || !encoded.matches("[A-Za-z0-9_-]+"))
      throw GatewayException.badRequest();
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded))
        throw GatewayException.badRequest();
      MfaResult[] values =
          mapper
              .readerFor(MfaResult[].class)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(bytes);
      if (values == null || values.length == 0 || values.length > policy.maxResults())
        throw GatewayException.badRequest();
      var ids = new HashSet<String>();
      for (var value : values) {
        if (value == null) throw GatewayException.badRequest();
        Credentials.identifier(value.authReqId());
        Credentials.identifier(value.tranID());
        Credentials.identifier(value.method());
        if (value.txId() != null) Credentials.identifier(value.txId());
        if (!ids.add(value.authReqId())) throw GatewayException.badRequest();
      }
      return List.of(values);
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw GatewayException.badRequest();
    }
  }
}
