package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.config.MfaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MfaResultParserTest {
  final MfaResultParser parser = new MfaResultParser(new MfaProperties(4096), new ObjectMapper());
  final String input =
      "{\"identifier\":{\"ssn\":\"s\",\"iPin\":\"i\"},\"ticketIds\":[\"t\"],\"code\":\"c\"}";

  String encode(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void acceptsTicketObjectAndAbsentHeader() {
    assertThat(parser.parse(List.of(encode(input))).ticketIds()).containsExactly("t");
    assertThat(parser.parse(List.of())).isNull();
  }

  @Test
  void numericTicketAndCodeAreNotCoercedToStrings() {
    for (String json :
        List.of(input.replace("[\"t\"]", "[1]"), input.replace("\"code\":\"c\"", "\"code\":1")))
      assertThatThrownBy(() -> parser.parse(List.of(encode(json)))).hasMessage("INVALID_ARGUMENT");
  }

  @Test
  void rejectsMalformedAmbiguousAndOldInputs() {
    for (var headers :
        List.of(
            List.of(""),
            List.of("a", "b"),
            List.of("a".repeat(4097)),
            List.of(encode("[" + input + "]")),
            List.of(encode("null")),
            List.of(encode(input + "{}")),
            List.of(encode(input.replace("[\"t\"]", "[]"))),
            List.of(encode(input.replace("[\"t\"]", "[\"t\",\"t\"]"))),
            List.of(encode(input.replace("\"ssn\":\"s\"", "\"ssn\":\"s\",\"ssn\":\"other\""))),
            List.of(encode(input.replace("\"code\":\"c\"", "\"unexpected\":true"))),
            List.of(encode(input.replace("\"iPin\":\"i\"", "\"iPin\":null"))))) {
      assertThatThrownBy(() -> parser.parse(headers)).hasMessage("INVALID_ARGUMENT");
    }
  }
}
