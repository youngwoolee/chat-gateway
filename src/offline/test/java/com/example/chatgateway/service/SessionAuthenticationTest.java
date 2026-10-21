package com.example.chatgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
      new SessionSettings(
          Duration.ofMinutes(5),
          Duration.ofMinutes(10),
          "shinhancard.com",
          "/",
          "out",
          "in",
          Set.of("demo"));
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
    when(platform.homepage(any(), any()))
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
        new Session(
            Credentials.hash(sid),
            "guest",
            "GUEST",
            "channel",
            "ACTIVE",
            0,
            300000,
            null,
            "thread"),
        null);
    return sid;
  }

  Exchange request(String sid, boolean identity) {
    var builder =
        MockMvcRequestBuilders.post("https://chat/api/chat?channelId=channel")
            .header("Idempotency-Key", "key");
    if (sid != null) builder.cookie(new Cookie(SessionCookies.NAME, sid));
    if (identity)
      builder
          .header("X-Requested-Login", "true")
          .cookie(
              new Cookie("PlatformSystemCode_O", "DEMO"),
              new Cookie("PlatformAuthentication_O", "ticket"),
              new Cookie("PlatformVerify_O", "proof"));
    return Exchange.from(builder);
  }

  @Test
  void cookieLossRequiresFreshIdentityAndNeverRestoresGuestOwnership() {
    String sid = guest();
    var previous = store.find(Credentials.hash(sid));
    when(platform.authenticate(any(), any())).thenReturn("member");
    var memberRequest = request(null, true);
    memberRequest.getRequest().setQueryString("channelId=channel&threadId=thread");
    var member = createOrReuse(auth, memberRequest);
    assertThat(member.threadId()).isEqualTo("thread");
    assertThat(member.subject()).isEqualTo("member");
    assertThat(member.guestSubject()).isNull();
    assertThat(member.idHash()).isNotEqualTo(previous.idHash());
    var guestRequest = request(null, false);
    guestRequest.getRequest().removeHeader("Idempotency-Key");
    guestRequest.getRequest().addHeader("Idempotency-Key", "new-guest");
    guestRequest.getRequest().setQueryString("channelId=channel&threadId=new-thread");
    var fresh = createOrReuse(auth, guestRequest);
    assertThat(fresh.threadId()).isEqualTo("new-thread");
    assertThat(fresh.subject()).isNotEqualTo(previous.subject());
    assertThat(fresh.guestSubject()).isNull();
  }

  @Test
  void differentThreadCannotPromoteOrExtendSession() {
    String sid = guest();
    var before = store.find(Credentials.hash(sid));
    assertThatThrownBy(() -> authenticateActivity(auth, request(sid, true), "other-thread"))
        .hasMessage("CONFLICT");
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    verifyNoInteractions(platform);
  }

  @Test
  void promotionRotatesSessionAndPreservesTrustedGuestWithoutBackendCall() {
    String sid = guest();
    when(platform.authenticate(any(), any())).thenReturn("member");
    var e = request(sid, true);
    var member = authenticateActivity(auth, e, "thread");
    assertThat(member.guestSubject()).isEqualTo("guest");
    assertThat(member.threadId()).isEqualTo("thread");
    assertThat(member.subject()).isEqualTo("member");
    assertThat(store.find(Credentials.hash(sid)).state()).isEqualTo("REVOKED");
    assertThat(e.getResponse().getCookie(SessionCookies.NAME).getValue()).isNotEqualTo(sid);
    assertThatThrownBy(() -> authenticateActivity(auth, request(sid, true), "thread"))
        .hasMessage("UNAUTHENTICATED");
  }

  @Test
  void slowPlatformAuthenticationCannotReviveExpiredGuest() {
    String sid = guest();
    when(platform.authenticate(any(), any()))
        .thenAnswer(
            inv -> {
              now = 300000;
              return "member";
            });
    var e = request(sid, true);
    assertThatThrownBy(() -> authenticateActivity(auth, e, "thread")).hasMessage("UNAUTHENTICATED");
    assertThat(e.getResponse().getCookies()).isEmpty();
  }

  @Test
  void statusNeverPromotesGuest() {
    String sid = guest();
    var e = request(sid, true);
    assertThat(inspect(auth, e).userType()).isEqualTo("GUEST");
    verifyNoInteractions(platform);
    assertThat(store.find(Credentials.hash(sid)).expiresAt()).isEqualTo(300000);
  }

  @Test
  void authoritativeOutagePreventsPlatformConsumption() {
    var authority = mock(AuthorityStore.class);
    org.mockito.Mockito.doThrow(GatewayException.unavailable()).when(authority).checkAvailable();
    var unavailable = service(authority);
    var e = request(null, true);
    when(platform.authenticate(any(), any()))
        .thenThrow(new AssertionError("Must not call platform authentication"));
    assertThatThrownBy(() -> createOrReuse(unavailable, e)).hasMessage("UNAVAILABLE");
    assertThat(e.getResponse().getCookies()).isEmpty();
  }

  @Test
  void authenticationRetryDoesNotExtendNewSession() {
    var e = request(null, false);
    Session created = createOrReuse(auth, e);
    String sid = e.getResponse().getCookie(SessionCookies.NAME).getValue();
    now = 2000;
    assertThat(createOrReuse(auth, request(sid, false)).expiresAt()).isEqualTo(created.expiresAt());
    assertThatThrownBy(() -> createOrReuse(auth, request(null, false))).hasMessage("CONFLICT");
  }

  @ParameterizedTest
  @CsvSource({
    "create,partial",
    "inspect,partial",
    "activity,partial",
    "create,duplicate",
    "inspect,duplicate",
    "activity,duplicate"
  })
  void memberIgnoresUnusableIdentityForEveryEntryPoint(String entry, String identity) {
    String sid = Credentials.random();
    store.create(
        new Session(
            Credentials.hash(sid),
            "member",
            "MEMBER",
            "channel",
            "ACTIVE",
            0,
            300000,
            null,
            "thread"),
        null);
    var builder =
        MockMvcRequestBuilders.post("https://chat/api/auth/session")
            .cookie(new Cookie(SessionCookies.NAME, sid), new Cookie("PlatformVerify_O", "bad"));
    if (identity.equals("duplicate")) builder.cookie(new Cookie("PlatformVerify_O", "other"));
    if (!entry.equals("inspect")) builder.header("Idempotency-Key", "key");
    var exchange = Exchange.from(builder);
    Session result =
        switch (entry) {
          case "create" -> createOrReuse(auth, exchange);
          case "inspect" -> inspect(auth, exchange);
          default -> authenticateActivity(auth, exchange, "thread");
        };
    assertThat(result.subject()).isEqualTo("member");
    assertThat(result.idHash()).isEqualTo(Credentials.hash(sid));
    assertThat(result.expiresAt()).isEqualTo(entry.equals("inspect") ? 300000 : now + 300000);
    assertThat(exchange.getResponse().getCookie(SessionCookies.NAME)).isNull();
    assertThat(exchange.getResponse().getCookies()).hasSize(entry.equals("inspect") ? 0 : 3);
    verify(platform, org.mockito.Mockito.never()).authenticate(any(), any());
  }

  @Test
  void inspectGuestNeedsNoKeyAndDoesNotParseIdentityOrExtend() {
    String sid = guest();
    var exchange =
        Exchange.from(
            MockMvcRequestBuilders.get("https://chat/api/auth/session")
                .cookie(
                    new Cookie(SessionCookies.NAME, sid),
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
  void guestOnlyPromotesOnExplicitChatLogin() {
    String sid = guest();
    var sessionRequest = request(sid, true);
    assertThat(createOrReuse(auth, sessionRequest).userType()).isEqualTo("GUEST");
    for (String flag : new String[] {"absent", "false"}) {
      var chat = request(sid, true);
      chat.getRequest().removeHeader("X-Requested-Login");
      if (flag.equals("false")) chat.getRequest().addHeader("X-Requested-Login", flag);
      assertThat(authenticateActivity(auth, chat, "thread").userType()).isEqualTo("GUEST");
    }
    var tool = request(sid, true);
    tool.getRequest().setRequestURI("/api/chat/threads/thread/tools/tool");
    assertThat(authenticateActivity(auth, tool, "thread").userType()).isEqualTo("GUEST");
    verifyNoInteractions(platform);
  }

  @Test
  void requestedLoginRequiresIdentityAndRejectsAmbiguousHeader() {
    String sid = guest();
    Session before = store.find(Credentials.hash(sid));
    var missing = request(sid, false);
    missing.getRequest().addHeader("X-Requested-Login", "true");
    assertThatThrownBy(() -> authenticateActivity(auth, missing, "thread"))
        .hasMessage("UNAUTHENTICATED");
    var invalid = request(sid, true);
    invalid.getRequest().addHeader("X-Requested-Login", "false");
    assertThatThrownBy(() -> authenticateActivity(auth, invalid, "thread"))
        .hasMessage("INVALID_ARGUMENT");
    assertThat(store.find(Credentials.hash(sid))).isEqualTo(before);
    verifyNoInteractions(platform);
  }

  @Test
  void chatLoginPromotesWithoutThreadTransferAndRetryDoesNotExtend() {
    String sid = guest();
    when(platform.authenticate(any(), any())).thenReturn("member");
    var exchange = request(sid, true);
    Session member = authenticateActivity(auth, exchange, "thread");
    String replacement = exchange.getResponse().getCookie(SessionCookies.NAME).getValue();
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
    assertThat(createOrReuse(auth, request(replacement, false)).expiresAt())
        .isEqualTo(member.expiresAt());
    assertThat(authenticateActivity(auth, request(replacement, false), "thread").expiresAt())
        .isEqualTo(member.expiresAt());
  }

  @Test
  void postSessionRecreatesNaturallyExpiredActiveButActivityRejectsIt() {
    String sid = guest();
    now = 300000;
    var chat = request(sid, true);
    assertThatThrownBy(() -> authenticateActivity(auth, chat, "thread"))
        .hasMessage("UNAUTHENTICATED");
    assertThat(chat.getResponse().getCookies()).isEmpty();
    var create = request(sid, false);
    Session replacement = createOrReuse(auth, create);
    assertThat(replacement.idHash()).isNotEqualTo(Credentials.hash(sid));
    assertThat(replacement.expiresAt()).isEqualTo(600000);
    assertThat(replacement.userType()).isEqualTo("GUEST");
    verifyNoInteractions(platform);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "revoked", "missing"})
  void untrustedSessionCannotFallBackDuringExistingConversation(String state) {
    String sid =
        state.equals("missing") ? null : state.equals("unknown") ? Credentials.random() : guest();
    if (state.equals("revoked")) store.revoke(Credentials.hash(sid));
    var exchange = request(sid, true);
    assertThatThrownBy(() -> authenticateActivity(auth, exchange, "thread"))
        .hasMessage("UNAUTHENTICATED");
    if (!state.equals("missing"))
      assertThatThrownBy(() -> createOrReuse(auth, exchange)).hasMessage("UNAUTHENTICATED");
    assertThat(exchange.getResponse().getCookies()).isEmpty();
    verifyNoInteractions(platform);
  }

  @Test
  void failedPlatformAuthenticationLeavesGuestActive() {
    String sid = guest();
    when(platform.authenticate(any(), any())).thenThrow(GatewayException.unauthorized());
    assertThatThrownBy(() -> authenticateActivity(auth, request(sid, true), "thread"))
        .hasMessage("UNAUTHENTICATED");
    assertThat(store.find(Credentials.hash(sid)).state()).isEqualTo("ACTIVE");
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
    assertThat(inspect(auth, e).channel()).isEqualTo("channel");
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

  Session authenticateActivity(SessionAuthentication service, Exchange e, String thread) {
    return service.authenticateActivity(e.getRequest(), e.getResponse(), thread);
  }
}
