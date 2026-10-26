package com.example.chatgateway.service;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.pfc.PfcClient.IdentityCookies;
import com.example.chatgateway.config.ChannelProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.cookie.SessionCookies;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 요청의 세션 우선 인증과 쿠키·만료 헤더 반영을 담당한다. 대화 소유권은 전달된 신원을 기반으로 B/E가 처리한다. */
@Service
public class SessionAuthentication {
  private final AuthorityStore store;
  private final SessionCookies cookies;
  private final ChannelProperties channels;
  private final PfcClient platform;
  private final SessionSettings settings;
  private final Clock clock;
  private final ChannelAccessService access;

  /** 세션 서비스 계약·쿠키·외부 연동과 시계를 주입하여 요청 인증 흐름을 구성한다. */
  public SessionAuthentication(
      AuthorityStore store,
      SessionCookies cookies,
      ChannelProperties channels,
      PfcClient platform,
      SessionSettings settings,
      Clock clock,
      ChannelAccessService access) {
    this.store = store;
    this.cookies = cookies;
    this.channels = channels;
    this.platform = platform;
    this.settings = settings;
    this.clock = clock;
    this.access = access;
  }

  /** _O 입력은 기존 세션보다 우선하며 로그인 실패는 새 게스트로 처리한다. */
  public Session createOrReuse(HttpServletRequest request, HttpServletResponse response) {
    String channel = requestChannel(request);
    if (cookies.hasIdentity(request)) return createSession(request, response, channel);
    Session session = findExisting(request, response, channel);
    if (session == null) return createSession(request, response, channel);
    requireUnexpired(response, session);
    access.require(session);
    return refreshSession(response, session);
  }

  public Session inspect(HttpServletRequest request, HttpServletResponse response) {
    Session session = requireActive(request, response);
    setSessionHeaders(response, session);
    return session;
  }

  /** 대화 ID와 무관하게 로그인 세션의 활동만 연장한다. */
  public Session authenticateActivity(HttpServletRequest request, HttpServletResponse response) {
    Session session = requireActive(request, response);
    return refreshSession(response, session);
  }

  /** SID가 없을 때만 null을 반환한다. 알 수 없거나 폐기된 SID는 신규 생성으로 우회하지 않는다. */
  private Session findExisting(
      HttpServletRequest request, HttpServletResponse response, String channel) {
    String sid = cookies.session(request, channel);
    if (sid == null) return null;
    Session session = store.find(Credentials.hash(sid));
    if (session == null) {
      cookies.clear(response, channel);
      throw GatewayException.unauthorized();
    }
    if (!session.channel().equals(channel)) throw GatewayException.denied();
    if (!"ACTIVE".equals(session.state())) {
      cookies.clear(response, channel);
      throw GatewayException.unauthorized();
    }
    return session;
  }

  /** 조회·대화는 기존 유효 세션이 필수이며 만료 세션을 새로 만들지 않는다. */
  private Session requireActive(HttpServletRequest request, HttpServletResponse response) {
    Session session = findExisting(request, response, requestChannel(request));
    if (session == null) throw GatewayException.unauthorized();
    requireUnexpired(response, session);
    access.require(session);
    return session;
  }

  private void requireUnexpired(HttpServletResponse response, Session session) {
    if (session.expiresAt() <= clock.millis()) {
      cookies.clear(response, session.channel());
      throw GatewayException.unauthorized();
    }
  }

  /** 유효한 활동 요청마다 저장소의 원자적 활동 연장을 사용한다. */
  private Session extendSession(HttpServletResponse response, Session session) {
    try {
      return requireSession(
          store.touch(session.idHash(), clock.millis(), settings.idleTimeout().toMillis(), null));
    } catch (GatewayException error) {
      if (error.code() == 401) {
        cookies.clear(response, session.channel());
        response.setHeader("X-Chat-User-Type", null);
        response.setHeader("X-Chat-Auth-Expires-At", null);
      }
      throw error;
    }
  }

  /** 쿠키 생성에 성공한 뒤 세션을 연장하고 응답에 반영한다. */
  private Session refreshSession(HttpServletResponse response, Session session) {
    IdentityCookies loginCookies = session.member() ? createLoginCookies(session.subject()) : null;
    Session updated = extendSession(response, session);
    return applySession(response, updated, loginCookies);
  }

  /** 저장·연장이 완료된 세션의 헤더와 준비된 로그인 쿠키를 응답에 반영한다. */
  private Session applySession(
      HttpServletResponse response, Session session, IdentityCookies loginCookies) {
    setSessionHeaders(response, session);
    if (loginCookies != null) cookies.writeLoginCookies(response, loginCookies);
    return session;
  }

  /** 채널·대화 query 값의 중복을 거절하고 생략된 경우 null을 반환한다. */
  private String queryHint(HttpServletRequest exchange, String parameter) {
    try {
      var hints = new ArrayList<String>();
      UriComponentsBuilder.fromUriString(
              "/?" + (exchange.getQueryString() == null ? "" : exchange.getQueryString()))
          .build()
          .getQueryParams()
          .forEach(
              (name, values) -> {
                if (parameter.equals(UriUtils.decode(name, StandardCharsets.UTF_8)))
                  hints.addAll(values);
              });
      if (hints.size() > 1) throw GatewayException.badRequest();
      if (hints.isEmpty() || hints.getFirst() == null) return null;
      return UriUtils.decode(hints.getFirst(), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformed) {
      throw GatewayException.badRequest();
    }
  }

  /** 허용 목록과 현재 지원하는 PFC 인증 유형을 확인한다. 임의 채널로 세션을 생성하지 못하게 한다. */
  private String channel(String value) {
    channels.require(value);
    return value;
  }

  private String requestChannel(HttpServletRequest request) {
    String value = queryHint(request, "channelId");
    if (value == null || value.isBlank()) throw GatewayException.badRequest();
    return channel(value);
  }

  /** 로그인 검증 실패만 게스트로 전환한다. 저장소·접근 권한 실패는 그대로 거절한다. */
  private Session createSession(
      HttpServletRequest request, HttpServletResponse response, String channel) {
    store.checkAvailable();
    String user = null;
    boolean verified = false;
    try {
      IdentityCookies identity = cookies.identity(request);
      if (identity != null) {
        user = platform.getUserId(identity);
        verified = user != null && user.matches("P[0-9]{9}");
      }
    } catch (RuntimeException loginFailure) {
      // start의 로그인 실패만 게스트로 처리하며 업무 통합인증에는 적용하지 않는다.
    }
    if (!verified) user = null;
    access.require(channel, user, verified);
    String sid = Credentials.random();
    Session previous = verified ? previousGuest(request, channel) : null;
    IdentityCookies loginCookies = verified ? createLoginCookies(user) : null;
    long now = clock.millis();
    Session proposed =
        new Session(
            Credentials.hash(sid),
            user,
            verified ? "MEMBER" : "GUEST",
            channel,
            "ACTIVE",
            now,
            now + settings.idleTimeout().toMillis(),
            previous == null ? null : previous.subject());
    Session saved =
        requireSession(
            previous == null
                ? store.create(proposed, null)
                : store.replaceGuest(previous.idHash(), null, proposed, now));
    if (!saved.member() && !Credentials.isGuestId(saved.subject()))
      throw GatewayException.badGateway();
    cookies.issue(response, sid, channel);
    if (verified) cookies.clearAuthCookies(response);
    return applySession(response, saved, loginCookies);
  }

  /** 이전 게스트 신원은 같은 채널의 유효한 서버 세션에서만 얻는다. */
  private Session previousGuest(HttpServletRequest request, String channel) {
    String sid;
    try {
      sid = cookies.session(request, channel);
    } catch (GatewayException invalidCookie) {
      return null;
    }
    if (sid == null) return null;
    Session previous = store.find(Credentials.hash(sid));
    return previous != null
            && !previous.member()
            && previous.channel().equals(channel)
            && "ACTIVE".equals(previous.state())
            && previous.expiresAt() > clock.millis()
        ? previous
        : null;
  }

  /** 저장소를 변경하기 전에 회원 로그인 쿠키 값을 생성하고 검증한다. */
  private IdentityCookies createLoginCookies(String userId) {
    IdentityCookies identity = platform.createLoginCookie(userId);
    if (identity == null) throw GatewayException.badGateway();
    for (String value :
        new String[] {identity.systemCode(), identity.authentication(), identity.verify()})
      if (value == null || value.isBlank()) throw GatewayException.badGateway();
    return identity;
  }

  /** 유효성 검사와 활동 연장을 최종 저장소에서 원자 처리하고 만료 헤더를 반영한다. */
  public Session activity(HttpServletResponse response, Session s) {
    Session updated = extendSession(response, s);
    setSessionHeaders(response, updated);
    return updated;
  }

  /** 프론트의 만료 안내에 필요한 서버 시각·세션 만료 시각을 밀리초로 제공하고 인증 응답 캐시를 금지한다. */
  private void setSessionHeaders(HttpServletResponse response, Session s) {
    response.setHeader("X-Chat-User-Type", s.userType());
    response.setHeader("X-Chat-Auth-Expires-At", Long.toString(s.expiresAt()));
    response.setHeader("X-Server-Time", Long.toString(clock.millis()));
    response.setHeader("Cache-Control", "no-store");
  }

  /** 필수 저장 결과가 없으면 연동 오류로 거절한다. */
  private static Session requireSession(Session session) {
    if (session == null) throw GatewayException.badGateway();
    return session;
  }
}
