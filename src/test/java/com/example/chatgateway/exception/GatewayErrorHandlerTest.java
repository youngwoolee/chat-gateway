package com.example.chatgateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayErrorHandlerTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final GatewayErrorHandler handler = new GatewayErrorHandler(mapper);

  @ParameterizedTest
  @CsvSource({
    "409,400,INVALID_ARGUMENT",
    "413,400,INVALID_ARGUMENT",
    "415,400,INVALID_ARGUMENT",
    "401,401,UNAUTHENTICATED",
    "403,403,PERMISSION_DENIED",
    "502,503,UNAVAILABLE",
    "504,503,UNAVAILABLE",
    "503,503,UNAVAILABLE",
    "404,404,NOT_FOUND",
    "500,500,INTERNAL"
  })
  void frontendHasOneErrorNamePerHttpCode(int internal, int expected, String status)
      throws Exception {
    var response = new MockHttpServletResponse();
    handler.handleRequest(
        new MockHttpServletRequest("POST", "/api/chat"),
        response,
        new GatewayException(internal, "INTERNAL_DETAIL"));
    var error = mapper.readTree(response.getContentAsByteArray()).path("error");
    assertThat(response.getStatus()).isEqualTo(expected);
    assertThat(error.path("code").asInt()).isEqualTo(expected);
    assertThat(error.path("status").asText()).isEqualTo(status);
  }

  @Test
  void internalContractAndSafeMfaFailureIdsArePreserved() throws Exception {
    var response = new MockHttpServletResponse();
    handler.handleRequest(
        new MockHttpServletRequest("POST", "/internal/v1/auth/operations"),
        response,
        GatewayException.conflict());
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(
            mapper.readTree(response.getContentAsByteArray()).path("error").path("status").asText())
        .isEqualTo("CONFLICT");
    response = new MockHttpServletResponse();
    handler.handle(response, GatewayException.mfaFailure("MFA_INVALID", List.of("ar_1")));
    var error = mapper.readTree(response.getContentAsByteArray()).path("error");
    assertThat(error.path("status").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(error.path("failedAuthReqIds").get(0).asText()).isEqualTo("ar_1");
  }
}
