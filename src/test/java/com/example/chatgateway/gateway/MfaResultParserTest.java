package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.config.MfaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MfaResultParserTest {
  final MfaResultParser parser =
      new MfaResultParser(
          new MfaProperties(
              Duration.ofMinutes(10),
              Duration.ofMinutes(2),
              8,
              4096,
              Set.of("FINANCE_CA", "SHINHAN")),
          new ObjectMapper());

  String encode(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void parseRejectsDuplicateIdsUnknownFieldsAndOversize() {
    String id = "ar_a";
    String one = "{\"authReqId\":\"" + id + "\",\"tranID\":\"T\",\"method\":\"SHINHAN\"}";
    assertThat(parser.parse(List.of(encode("[" + one + "]")))).hasSize(1);
    for (var headers :
        List.of(
            List.of("a", "b"),
            List.of("a".repeat(4097)),
            List.of(encode("[" + one + "," + one + "]")),
            List.of(encode("[]")),
            List.of(encode("[{\"authReqId\":\"a\",\"authReqId\":\"b\"}]")),
            List.of(
                encode(
                    "[{\"authReqId\":\"a\",\"tranID\":\"T\",\"method\":\"SHINHAN\",\"extra\":true}]")))) {
      assertThatThrownBy(() -> parser.parse(headers)).hasMessage("INVALID_ARGUMENT");
    }
  }

  @Test
  void missingHeaderNeedsNoMfa() {
    assertThat(parser.parse(List.of())).isEmpty();
  }
}
