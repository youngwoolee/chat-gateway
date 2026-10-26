package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.config.AuthType;
import com.example.chatgateway.config.ChannelProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.session.LocalAuthorityStore;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChannelSessionTest {
  static final String A = "11111111-1111-4111-8111-111111111111";
  static final String B = "22222222-2222-4222-8222-222222222222";
  static final String COOKIE_A = "__Host-sh-session_11111111";
  static final String COOKIE_B = "__Host-sh-session_22222222";
  final LocalAuthorityStore store = new LocalAuthorityStore();
  final PfcClient pfc = mock(PfcClient.class);
  final Clock clock = mock(Clock.class);
  final SessionSettings settings =
      new SessionSettings(Duration.ofMinutes(5), "shinhancard.com", "/");
  final SessionAuthentication auth =
      new SessionAuthentication(
          store,
          new SessionCookies(settings),
          new ChannelProperties(Map.of(A, AuthType.PFC, B, AuthType.PFC)),
          pfc,
          settings,
          clock,
          mock(ChannelAccessService.class));

  MockHttpServletRequest request(String channel, Cookie... cookies) {
    var request = new MockHttpServletRequest("POST", "/api/auth/session");
    request.setQueryString("channelId=" + channel);
    request.setCookies(cookies);
    return request;
  }

  Cookie create(String channel, String name, Cookie... cookies) {
    var response = new MockHttpServletResponse();
    auth.createOrReuse(request(channel, cookies), response);
    Cookie cookie = response.getCookie(name);
    assertThat(cookie).isNotNull();
    assertThat(cookie.getSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    return cookie;
  }

  @Test
  void bothChannelCookiesCanCoexistAndReuseIndependentlyWithoutRequestKeys() {
    Cookie a = create(A, COOKIE_A);
    Cookie b = create(B, COOKIE_B, a);
    assertThat(a.getValue()).isNotEqualTo(b.getValue());
    Session first = auth.inspect(request(A, a, b), new MockHttpServletResponse());
    Session second = auth.inspect(request(B, a, b), new MockHttpServletResponse());
    assertThat(first.channel()).isEqualTo(A);
    assertThat(second.channel()).isEqualTo(B);
    assertThat(first.subject()).isNotEqualTo(second.subject());
    when(clock.millis()).thenReturn(2000L);
    var activity = request(A, a, b);
    assertThat(auth.authenticateActivity(activity, new MockHttpServletResponse()).expiresAt())
        .isEqualTo(302000L);
    assertThat(store.find(second.idHash())).isEqualTo(second);
  }

  @Test
  void loginRotatesOnlyTheSelectedChannel() {
    Cookie a = create(A, COOKIE_A);
    Cookie b = create(B, COOKIE_B, a);
    Session second = auth.inspect(request(B, a, b), new MockHttpServletResponse());
    when(pfc.getUserId(any())).thenReturn("P123456789");
    when(pfc.createLoginCookie(any())).thenReturn(new PfcClient.IdentityCookies("CBA", "ticket", "proof"));
    var request =
        request(
            A,
            a,
            b,
            new Cookie("PlatformSystemCode_O", "DEMO"),
            new Cookie("PlatformAuthentication_O", "ticket"),
            new Cookie("PlatformVerify_O", "proof"));
    var response = new MockHttpServletResponse();
    assertThat(auth.createOrReuse(request, response).member()).isTrue();
    assertThat(response.getCookie(COOKIE_A).getValue()).isNotEqualTo(a.getValue());
    assertThat(response.getCookie(COOKIE_B)).isNull();
    assertThat(store.find(second.idHash())).isEqualTo(second);
  }

  @Test
  void missingChannelAndDuplicateChannelAreRejectedAndForeignSidCannotBeRelabeled() {
    Cookie a = create(A, COOKIE_A);
    var missing = request(A, a);
    missing.setQueryString(null);
    assertThatThrownBy(() -> auth.inspect(missing, new MockHttpServletResponse()))
        .hasMessage("INVALID_ARGUMENT");
    var duplicate = request(A, a);
    duplicate.setQueryString("channelId=" + A + "&%63hannelId=" + B);
    assertThatThrownBy(() -> auth.inspect(duplicate, new MockHttpServletResponse()))
        .hasMessage("INVALID_ARGUMENT");
    assertThatThrownBy(
            () ->
                auth.inspect(
                    request(B, new Cookie(COOKIE_B, a.getValue())), new MockHttpServletResponse()))
        .hasMessage("PERMISSION_DENIED");
  }

  @Test
  void configuredChannelPrefixesMustBeUnique() {
    assertThatThrownBy(
            () ->
                new ChannelProperties(
                    Map.of(A, AuthType.PFC, "11111111-2222-4222-8222-222222222222", AuthType.PFC)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
