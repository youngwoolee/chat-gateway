package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.pfc.PfcClient.MfaInput;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MfaServiceTest {
  final PfcClient pfc = mock(PfcClient.class);
  final ObjectMapper mapper = new ObjectMapper();
  final MfaService service = new MfaService(pfc, new SessionCookies(null), mapper);
  final Session member =
      new Session("sid", "P123456789", "MEMBER", "channel", "ACTIVE", 0, 300000, null);
  final MfaInput input =
      new MfaInput(
          mapper.createObjectNode().put("ssn", "s").put("iPin", "i"),
          List.of("ticket"),
          "code",
          null,
          null);

  MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__state_as_id", "state"));
    return request;
  }

  byte[] body(String resume) {
    return ("{\"resume\":" + resume + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  String resolved = "[{\"interruptId\":\"int-3\",\"status\":\"resolved\"}]";

  @Test
  void verifiedIdentityBindsExactlyOneInterrupt() {
    when(pfc.verifyTicketId(input, "state")).thenReturn(member.subject());
    assertThat(service.verifyResume(member, request(), body(resolved), input)).isEqualTo("int-3");
    verify(pfc, times(1)).verifyTicketId(input, "state");
  }

  @Test
  void malformedResumeAndMissingStateNeverCallPfc() {
    for (String resume :
        List.of(
            "null",
            "[]",
            "{}",
            resolved.replace("resolved", "cancelled"),
            resolved.replace("int-3", ""),
            resolved.replace("]", ",{}]"))) {
      assertThatThrownBy(() -> service.verifyResume(member, request(), body(resume), input))
          .hasMessage("INVALID_ARGUMENT");
    }
    assertThatThrownBy(
            () -> service.verifyResume(member, new MockHttpServletRequest(), body(resolved), input))
        .hasMessage("INVALID_ARGUMENT");
    var duplicate = request();
    duplicate.setCookies(new Cookie("__state_as_id", "a"), new Cookie("__state_as_id", "b"));
    assertThatThrownBy(() -> service.verifyResume(member, duplicate, body(resolved), input))
        .hasMessage("INVALID_ARGUMENT");
    verifyNoInteractions(pfc);
  }

  @Test
  void guestCannotVerifyAndIdentityMismatchFailsClosed() {
    var guest = new Session("sid", "G000000000001", "GUEST", "channel", "ACTIVE", 0, 300000, null);
    assertThatThrownBy(() -> service.verifyResume(guest, request(), body(resolved), input))
        .hasMessage("MFA_INVALID");
    verifyNoInteractions(pfc);
    when(pfc.verifyTicketId(any(), anyString())).thenReturn("P987654321");
    assertThatThrownBy(() -> service.verifyResume(member, request(), body(resolved), input))
        .hasMessage("MFA_INVALID");
  }

  @Test
  void rejectionAndInfrastructureFailureStayDistinctWithoutRetries() {
    when(pfc.verifyTicketId(any(), anyString())).thenThrow(GatewayException.mfaInvalid());
    assertThatThrownBy(() -> service.verifyResume(member, request(), body(resolved), input))
        .hasMessage("MFA_INVALID");
    org.mockito.Mockito.doThrow(new IllegalStateException("transport"))
        .when(pfc)
        .verifyTicketId(any(), anyString());
    assertThatThrownBy(() -> service.verifyResume(member, request(), body(resolved), input))
        .hasMessage("UNAVAILABLE");
    verify(pfc, times(2)).verifyTicketId(any(), anyString());
  }
}
