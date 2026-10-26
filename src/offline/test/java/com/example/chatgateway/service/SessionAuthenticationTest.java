package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.config.AuthType;
import com.example.chatgateway.config.ChannelProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.LocalAuthorityStore;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class SessionAuthenticationTest {
  final LocalAuthorityStore store = new LocalAuthorityStore();
  final PfcClient platform = mock(PfcClient.class);
  final SessionSettings settings =
      new SessionSettings(Duration.ofMinutes(5), "shinhancard.com", "/");
  long now = 1000;
  Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneId.of("UTC");
        }

        public Clock withZone(ZoneId z) {
          return this;
        }

        public Instant instant() {
          return Instant.ofEpochMilli(now);
        }
      };
  SessionAuthentication auth;

  @BeforeEach
  void setup() {
    when(platform.createLoginCookie(any()))
        .thenReturn(new PfcClient.IdentityCookies("CBA", "ticket", "proof"));
    auth = service(store);
  }

  SessionAuthentication service(AuthorityStore authority) {
    return new SessionAuthentication(
        authority,
        new SessionCookies(settings),
        new ChannelProperties(Map.of("channel", AuthType.PFC)),
        platform,
        settings,
        clock,
        org.mockito.Mockito.mock(ChannelAccessService.class));
  }

  String guest() {
    String sid = Credentials.random();
    store.create(
        new Session(Credentials.hash(sid), "guest", "GUEST", "channel", "ACTIVE", 0, 300000, null),
        null);
    return sid;
  }

  Exchange request(String sid, boolean identity) {
    var builder = MockMvcRequestBuilders.post("https://chat/api/chat?channelId=channel");
    if (sid != null) builder.cookie(new Cookie(SessionCookies.name("channel"), sid));
    if (identity)
      builder.cookie(
          new Cookie("PlatformSystemCode_O", "DEMO"),
          new Cookie("PlatformAuthentication_O", "ticket"),
          new Cookie("PlatformVerify_O", "proof"));
    return Exchange.from(builder);
  }

  @Test
  void cookieLossRequiresFreshIdentityAndNeverRestoresGuestOwnership() {
    String sid = guest();
    var previous = store.find(Credentials.hash(sid));
    when(platform.getUserId(any())).thenReturn("P123456789");
    var memberRequest = request(null, true);
    var member = createOrReuse(auth, memberRequest);
    assertThat(member.subject()).isEqualTo("P123456789");
    assertThat(member.guestSubject()).isNull();
    assertThat(member.idHash()).isNotEqualTo(previous.idHash());
    var guestRequest = request(null, false);
    var fresh = createOrReuse(auth, guestRequest);
    assertThat(fresh.subject()).isNotEqualTo(previous.subject());
    assertThat(fresh.guestSubject()).isNull();
  }

  @Test
  void promotionRotatesSessionAndPreservesTrustedGuestWithoutBackendCall() {
    String sid = guest();
    when(platform.getUserId(any())).thenReturn("P123456789");
    var e = request(sid, true);
    var member = createOrReuse(auth, e);
    assertThat(member.guestSubject()).isEqualTo("guest");
    assertThat(member.subject()).isEqualTo("P123456789");
    assertThat(store.find(Credentials.hash(sid)).state()).isEqualTo("REVOKED");
    assertThat(e.getResponse().getCookie(SessionCookies.name("channel")).getValue())
        .isNotEqualTo(sid);
    assertThatThrownBy(() -> authenticateActivity(auth, request(sid, true)))
        .hasMessage("UNAUTHENTICATED");
  }

  @Test
  void authoritativeOutagePreventsPlatformConsumption() {
    var authority = mock(AuthorityStore.class);
    org.mockito.Mockito.doThrow(GatewayException.unavailable()).when(authority).checkAvailable();
    var unavailable = service(authority);
    var e = request(null, true);
    when(platform.getUserId(any()))
        .thenThrow(new AssertionError("Must not call platform authentication"));
    assertThatThrownBy(() -> createOrReuse(unavailable, e)).hasMessage("UNAVAILABLE");
    assertThat(e.getResponse().getCookies()).isEmpty();
  }

  @Test
  void sessionRequestsWithoutKeysExtendOrCreateSessions() {
    var e = request(null, false);
    Session created = createOrReuse(auth, e);
    String sid = e.getResponse().getCookie(SessionCookies.name("channel")).getValue();
    now = 2000;
    assertThat(createOrReuse(auth, request(sid, false)).expiresAt()).isEqualTo(302000);
    assertThat(createOrReuse(auth, request(null, false)).idHash()).isNotEqualTo(created.idHash());
  }

  @Test
  void inspectGuestNeedsNoKeyAndDoesNotParseIdentityOrExtend() {
    String sid = guest();
    var exchange =
        Exchange.from(
            MockMvcRequestBuilders.get("https://chat/api/auth/session?channelId=channel")
                .cookie(
                    new Cookie(SessionCookies.name("channel"), sid),
                    new Cookie("PlatformVerify_O", "bad"),
                    new Cookie("PlatformVerify_O", "duplicate")));
    Session before = store.find(Credentials.hash(sid));
    assertThat(inspect(auth, exchange)).isEqualTo(before);
    now = 299999;
    assertThat(inspect(auth, exchange)).isEqualTo(before);
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    verifyNoInteractions(platform);
  }

  @Test
  void startLoginPromotesAndEveryNewActivityExtendsSession() {
    String sid = guest();
    when(platform.getUserId(any())).thenReturn("P123456789");
    var exchange = request(sid, true);
    Session member = createOrReuse(auth, exchange);
    String replacement =
        exchange.getResponse().getCookie(SessionCookies.name("channel")).getValue();
    assertThat(replacement).isNotEqualTo(sid);
    assertThat(member.userType()).isEqualTo("MEMBER");
    assertThat(store.find(Credentials.hash(sid)).state()).isEqualTo("REVOKED");
    for (String name :
        new String[] {"PlatformSystemCode_O", "PlatformAuthentication_O", "PlatformVerify_O"}) {
      var deleted = exchange.getResponse().getCookie(name);
      assertThat(deleted.getMaxAge()).isZero();
      assertThat(deleted.getDomain()).isEqualTo("shinhancard.com");
      assertThat(deleted.getPath()).isEqualTo("/");
    }
    now = 2000;
    assertThat(createOrReuse(auth, request(replacement, false)).expiresAt()).isEqualTo(302000);
    assertThat(authenticateActivity(auth, request(replacement, false)).expiresAt())
        .isEqualTo(302000);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "revoked", "missing"})
  void untrustedSessionCannotFallBackDuringExistingConversation(String state) {
    String sid =
        state.equals("missing") ? null : state.equals("unknown") ? Credentials.random() : guest();
    if (state.equals("revoked")) store.revoke(Credentials.hash(sid));
    var exchange = request(sid, true);
    assertThatThrownBy(() -> authenticateActivity(auth, exchange)).hasMessage("UNAUTHENTICATED");
    assertThat(createOrReuse(auth, request(sid, false)).userType()).isEqualTo("GUEST");
    if (state.equals("missing")) {
      assertThat(exchange.getResponse().getCookies()).isEmpty();
    } else {
      assertThat(exchange.getResponse().getCookie(SessionCookies.name("channel")).getMaxAge())
          .isZero();
    }
    verifyNoInteractions(platform);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingSessionCanRestartWithGuestOrVerifiedMember(boolean identity) {
    String oldSid = Credentials.random();
    when(platform.getUserId(any())).thenReturn("P123456789");
    var request = request(oldSid, identity);
    Session session = createOrReuse(auth, request);
    assertThat(session.userType()).isEqualTo(identity ? "MEMBER" : "GUEST");
    assertThat(request.getResponse().getCookie(SessionCookies.name("channel")).getValue())
        .isNotBlank().isNotEqualTo(oldSid);
  }

  @Test
  void storeFailureDuringStartIsNotTreatedAsMissingSession() {
    var authority = mock(AuthorityStore.class);
    when(authority.find(any())).thenThrow(GatewayException.unavailable());
    assertThatThrownBy(() -> createOrReuse(service(authority), request(Credentials.random(), false)))
        .hasMessage("UNAVAILABLE");
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.never()).create(any(), any());
  }

  @Test
  void absentLookupIsUnauthorizedAndMissingWriteResultCannotIssueCookie() {
    AuthorityStore missing = mock(AuthorityStore.class);
    var service = service(missing);
    var unknown = request(Credentials.random(), false);
    assertThatThrownBy(() -> inspect(service, unknown)).hasMessage("UNAUTHENTICATED");
    var create = request(null, false);
    assertThatThrownBy(() -> createOrReuse(service, create)).hasMessage("BAD_GATEWAY");
    assertThat(create.getResponse().getCookies()).isEmpty();
    verifyNoInteractions(platform);
  }

  @Test
  void decodedQueryNamesCannotHideDuplicateChannels() {
    var e = request(guest(), false);
    e.getRequest().setQueryString("channelId=channel&%63hannelId=channel");
    assertThatThrownBy(() -> inspect(auth, e)).hasMessage("INVALID_ARGUMENT");
    e.getRequest().setQueryString("channelId=%63hannel");
    assertThat(inspect(auth, e).channel()).isEqualTo("channel");
    e.getRequest().setQueryString("channelId");
    assertThatThrownBy(() -> inspect(auth, e)).hasMessage("INVALID_ARGUMENT");
  }

  @Test
  void startPrioritizesIdentityAndFallsBackOnlyForLoginFailures() {
    for (var error :
        java.util.List.of(
            GatewayException.unauthorized(),
            GatewayException.unavailable(),
            new IllegalStateException("PFC"))) {
      org.mockito.Mockito.doThrow(error).when(platform).getUserId(any());
      var exchange = request(guest(), true);
      Session created = createOrReuse(auth, exchange);
      assertThat(created.userType()).isEqualTo("GUEST");
      assertThat(created.subject()).matches("G[0-9]{12}");
      assertThat(exchange.getResponse().getCookie(SessionCookies.name("channel"))).isNotNull();
    }
    org.mockito.Mockito.doReturn("P123456789").when(platform).getUserId(any());
    var exchange = request(guest(), true);
    assertThat(createOrReuse(auth, exchange).userType()).isEqualTo("MEMBER");
  }

  @Test
  void partialIdentityAndInvalidPidCreateGuest() {
    var partial = request(null, false);
    partial.getRequest().setCookies(new Cookie("PlatformVerify_O", "only-one"));
    assertThat(createOrReuse(auth, partial).userType()).isEqualTo("GUEST");
    verifyNoInteractions(platform);
    for (String invalid : new String[] {"member", "P123", "P1234567890", "G123456789"}) {
      when(platform.getUserId(any())).thenReturn(invalid);
      assertThat(createOrReuse(auth, request(null, true)).userType()).isEqualTo("GUEST");
    }
  }

  @Test
  void expiredSessionRestartsOnlyOnSessionCreation() {
    String sid = guest();
    now = 300000;
    for (String operation : new String[] {"inspect", "activity"}) {
      var exchange = request(sid, false);
      assertThatThrownBy(
              () -> {
                switch (operation) {
                  case "inspect" -> inspect(auth, exchange);
                  default -> authenticateActivity(auth, exchange);
                }
              })
          .hasMessage("UNAUTHENTICATED");
      assertThat(exchange.getResponse().getCookie(SessionCookies.name("channel")).getMaxAge())
          .isZero();
    }
    assertThat(createOrReuse(auth, request(sid, false)).userType()).isEqualTo("GUEST");
    verifyNoInteractions(platform);
    when(platform.getUserId(any())).thenReturn("P123456789");
    assertThat(createOrReuse(auth, request(sid, true)).userType()).isEqualTo("MEMBER");
  }

  @Test
  void chatNeverUsesPlatformLogin() {
    String sid = guest();
    var exchange = request(sid, true);
    assertThat(authenticateActivity(auth, exchange).userType()).isEqualTo("GUEST");
    assertThat(exchange.getResponse().getHeader("X-Chat-User-Type")).isEqualTo("GUEST");
    verifyNoInteractions(platform);
  }

  @Test
  void atomicExpiryDuringTouchClearsCookieAndPreviouslySetIdentityHeaders() {
    String sid = guest();
    var authority = mock(AuthorityStore.class);
    when(authority.find(Credentials.hash(sid))).thenReturn(store.find(Credentials.hash(sid)));
    when(authority.touch(
            org.mockito.ArgumentMatchers.eq(Credentials.hash(sid)),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenThrow(GatewayException.unauthorized());
    var exchange = request(sid, false);
    assertThatThrownBy(() -> authenticateActivity(service(authority), exchange))
        .hasMessage("UNAUTHENTICATED");
    assertThat(exchange.getResponse().getCookie(SessionCookies.name("channel")).getMaxAge())
        .isZero();
    assertThat(exchange.getResponse().getHeader("X-Chat-User-Type")).isNull();
    assertThat(exchange.getResponse().getHeader("X-Chat-Auth-Expires-At")).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void loginCookieFailureDoesNotCreateOrExtendSession(boolean existing) {
    var authority = mock(AuthorityStore.class);
    String sid = Credentials.random();
    if (existing) {
      when(authority.find(Credentials.hash(sid)))
          .thenReturn(new Session(Credentials.hash(sid), "P123456789", "MEMBER",
              "channel", "ACTIVE", 0, 300000, null));
    } else {
      when(platform.getUserId(any())).thenReturn("P123456789");
    }
    when(platform.createLoginCookie(any())).thenReturn(null);
    var exchange = request(existing ? sid : null, !existing);
    assertThatThrownBy(() -> createOrReuse(service(authority), exchange))
        .hasMessage("BAD_GATEWAY");
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.never()).create(any(), any());
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.never())
        .replaceGuest(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.never())
        .touch(any(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), any());
    assertThat(exchange.getResponse().getHeaders("Set-Cookie")).isEmpty();
    assertThat(exchange.getResponse().getHeader("X-Chat-User-Type")).isNull();
  }

  record Exchange(MockHttpServletRequest getRequest, MockHttpServletResponse getResponse) {
    static Exchange from(MockHttpServletRequestBuilder builder) {
      return new Exchange(
          builder.buildRequest(new MockServletContext()), new MockHttpServletResponse());
    }
  }

  Session createOrReuse(SessionAuthentication service, Exchange e) {
    return service.createOrReuse(e.getRequest(), e.getResponse());
  }

  Session inspect(SessionAuthentication service, Exchange e) {
    return service.inspect(e.getRequest(), e.getResponse());
  }

  Session authenticateActivity(SessionAuthentication service, Exchange e) {
    return service.authenticateActivity(e.getRequest(), e.getResponse());
  }
}
