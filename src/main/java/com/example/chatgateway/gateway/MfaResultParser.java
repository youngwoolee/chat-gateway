package com.example.chatgateway.gateway;

import com.example.chatgateway.client.pfc.PfcClient.MfaInput;
import com.example.chatgateway.config.MfaProperties;
import com.example.chatgateway.exception.GatewayException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MfaResultParser {
  private final MfaProperties policy;
  private final ObjectMapper mapper;

  public MfaResultParser(MfaProperties policy, ObjectMapper mapper) {
    this.policy = policy;
    this.mapper = mapper;
  }

  public MfaInput parse(List<String> headers) {
    if (headers.isEmpty()) return null;
    if (headers.size() != 1) throw GatewayException.badRequest();
    String encoded = headers.getFirst();
    if (encoded.length() > policy.maxHeaderBytes() || !encoded.matches("[A-Za-z0-9_-]+"))
      throw GatewayException.badRequest();
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded))
        throw GatewayException.badRequest();
      var json =
          mapper
              .reader()
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(bytes);
      if (!json.isObject() || !json.path("code").isTextual() || !json.path("ticketIds").isArray())
        throw GatewayException.badRequest();
      for (var ticket : json.path("ticketIds"))
        if (!ticket.isTextual()) throw GatewayException.badRequest();
      for (String field : new String[] {"source", "componentId"})
        if (json.hasNonNull(field) && !json.path(field).isTextual())
          throw GatewayException.badRequest();
      MfaInput input =
          mapper
              .readerFor(MfaInput.class)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(json);
      if (input == null
          || input.identifier() == null
          || !input.identifier().isObject()
          || !input.identifier().path("ssn").isTextual()
          || input.identifier().path("ssn").asText().isBlank()
          || !input.identifier().path("iPin").isTextual()
          || input.identifier().path("iPin").asText().isBlank()
          || input.code() == null
          || input.code().isBlank()
          || input.ticketIds() == null
          || input.ticketIds().isEmpty()
          || input.ticketIds().stream().anyMatch(t -> t == null || t.isBlank())
          || new HashSet<>(input.ticketIds()).size() != input.ticketIds().size())
        throw GatewayException.badRequest();
      return input;
    } catch (Exception invalid) {
      throw GatewayException.badRequest();
    }
  }
}
