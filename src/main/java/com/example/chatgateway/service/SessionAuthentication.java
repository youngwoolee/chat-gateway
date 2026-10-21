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
import java.util.Collections;
import java.util.UUID;
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

  /** 세션을 생성하거나 재사용한다. 기존 게스트는 이 API에서 회원으로 전환하지 않는다. */
  public Session createOrReuse(HttpServletRequest request, HttpServletResponse response) {
    String thread = queryHint(request, "threadId");
    String channelHint = queryHint(request, "channelId");
    Session session = findExisting(request, channelHint);
    if (session == null || session.expiresAt() <= clock.millis()) {
      String channel = session == null ? channel(channelHint) : session.channel();
      session = createSession(request, response, channel, thread);
    } else {
      requireThread(session, thread);
      session = touch(session, "session:" + key(request));
    }
    return completeActivity(request, response, session);
  }

  /** 키 요구·활동 연장·플랫폼 인증 없이 현재 세션을 조회한다. */
  public Session inspect(HttpServletRequest request, HttpServletResponse response) {
    Session session = requireActive(request);
    headers(response, session);
    return session;
  }

  /** 대화 세션 검증 → 명시적 로그인 전환 또는 활동 연장 → 응답 쿠키 순으로 처리한다. */
  public Session authenticateActivity(
      HttpServletRequest request, HttpServletResponse response, String threadId) {
    boolean login = requestedLogin(request);
    Session session = requireActive(request);
    requireThread(session, threadId);
    if (login && !session.member()) {
      IdentityCookies identity = cookies.identity(request);
      if (identity == null) throw GatewayException.unauthorized();
      session = promoteGuest(request, response, session, identity);
    } else {
      session = touch(session, "chat:" + key(request));
    }
    return completeActivity(request, response, session);
  }

  /** SID가 없을 때만 null을 반환한다. 알 수 없거나 폐기된 SID는 신규 생성으로 우회하지 않는다. */
  private Session findExisting(HttpServletRequest request, String channelHint) {
    String sid = cookies.session(request);
    if (sid == null) return null;
    Session session = store.find(Credentials.hash(sid));
    if (session == null) throw GatewayException.unauthorized();
    String channel = channel(channelHint == null ? session.channel() : channelHint);
    if (!session.channel().equals(channel)) throw GatewayException.denied();
    if (!"ACTIVE".equals(session.state())) throw GatewayException.unauthorized();
    access.require(session);
    return session;
  }

  /** 조회·대화는 기존 유효 세션이 필수이며 만료 세션을 새로 만들지 않는다. */
  private Session requireActive(HttpServletRequest request) {
    Session session = findExisting(request, queryHint(request, "channelId"));
    if (session == null || session.expiresAt() <= clock.millis())
      throw GatewayException.unauthorized();
    return session;
  }

  /** 대화와 세션의 thread가 다르면 로그인 전환이나 활동 연장 전에 거절한다. */
  private void requireThread(Session session, String threadId) {
    if (threadId != null && !session.threadId().equals(Credentials.identifier(threadId)))
      throw GatewayException.conflict();
  }

  /** 로그인 전환 헤더는 chat에서만 허용된 단일 boolean 값으로 해석한다. */
  private boolean requestedLogin(HttpServletRequest request) {
    if (!"/api/chat".equals(request.getRequestURI())) return false;
    var values = Collections.list(request.getHeaders("X-Requested-Login"));
    if (values.size() > 1
        || (!values.isEmpty()
            && !"true".equals(values.getFirst())
            && !"false".equals(values.getFirst()))) throw GatewayException.badRequest();
    return !values.isEmpty() && "true".equals(values.getFirst());
  }

  /** 저장소의 원자적 활동 연장을 사용하며 동일 키의 재시도는 추가 연장하지 않는다. */
  private Session touch(Session session, String key) {
    return required(
        store.touch(session.idHash(), clock.millis(), settings.idleTimeout().toMillis(), key));
  }

  /** 생성·대화 응답에 회원 홈페이지 연동 쿠키와 만료 정보를 반영한다. */
  private Session completeActivity(
      HttpServletRequest request, HttpServletResponse response, Session session) {
    required(session);
    if (session.member()) issueHomepageCookies(response, session, key(request));
    headers(response, session);
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

  /** 최종 저장소의 사용 가능 여부를 확인한 뒤 게스트 또는 플랫폼이 검증한 회원 세션을 생성한다. 저장 확정 후에만 쿠키를 발급하고 사용한 _O를 삭제한다. */
  private Session createSession(
      HttpServletRequest e, HttpServletResponse response, String channel, String threadId) {
    String conversation =
        threadId == null ? UUID.randomUUID().toString() : Credentials.identifier(threadId);
    String key = key(e);
    IdentityCookies identity = cookies.identity(e);
    if (identity == null) access.require(channel, null, false);
    store.checkAvailable();
    String user =
        identity == null ? "guest-" + Credentials.random() : platform.authenticate(identity, key);
    if (user == null) throw GatewayException.unauthorized();
    access.require(channel, user, identity != null);
    String sid = Credentials.random();
    Session saved =
        required(
            store.create(
                newSession(
                    sid, user, identity == null ? "GUEST" : "MEMBER", channel, null, conversation),
                key));
    cookies.issue(response, sid);
    if (identity != null) cookies.consume(response);
    return saved;
  }

  /** PFC 인증 후 게스트 폐기와 회원 세션 생성을 원자 처리한다. 대화 이전은 호출하지 않는다. */
  private Session promoteGuest(
      HttpServletRequest exchange,
      HttpServletResponse response,
      Session guest,
      IdentityCookies identity) {
    String key = key(exchange);
    String member = platform.authenticate(identity, key);
    if (member == null) throw GatewayException.unauthorized();
    access.require(guest.channel(), member, true);
    String sid = Credentials.random();
    Session replacement =
        newSession(sid, member, "MEMBER", guest.channel(), guest.subject(), guest.threadId());
    Session saved = required(store.replaceGuest(guest.idHash(), key, replacement, clock.millis()));
    cookies.issue(response, sid);
    cookies.consume(response);
    return saved;
  }

  /** 헤더로 전달 가능한 사용자 ID인지 검사하고, 세션 ID 해시·사용자 유형·채널·threadId·활동 만료를 가진 신규 세션을 구성한다. */
  private Session newSession(
      String sid,
      String subject,
      String userType,
      String channel,
      String guestSubject,
      String threadId) {
    long now = clock.millis();
    if (subject == null
        || subject.isBlank()
        || subject.length() > 256
        || subject.chars().anyMatch(c -> c < 33 || c > 126)) throw GatewayException.badGateway();
    return new Session(
        Credentials.hash(sid),
        subject,
        userType,
        channel,
        "ACTIVE",
        now,
        now + settings.idleTimeout().toMillis(),
        guestSubject,
        threadId);
  }

  /** 회원 인증·대화 요청 응답에 홈페이지 연동 쿠키를 준비한다. 별도 활동 연장은 하지 않는다. */
  private void issueHomepageCookies(HttpServletResponse response, Session session, String key) {
    IdentityCookies identity = platform.homepage(session.subject(), key);
    if (identity == null) throw GatewayException.badGateway();
    for (String value :
        new String[] {identity.systemCode(), identity.authentication(), identity.verify()})
      if (value == null || value.isBlank()) throw GatewayException.badGateway();
    cookies.homepage(response, identity);
  }

  /** 유효한 서버 신원만 승계한다. 원자 교체·재시도 판정은 세션 서비스가 수행하며 PFC는 호출하지 않는다. */
  public Session restart(
      HttpServletRequest request, HttpServletResponse response, String threadId) {
    String thread = Credentials.identifier(threadId);
    String key = key(request);
    String sid = cookies.session(request);
    if (sid == null) throw GatewayException.unauthorized();
    Session source = store.find(Credentials.hash(sid));
    if (source == null) throw GatewayException.unauthorized();
    access.require(source);
    String candidate = Credentials.restartSid(sid, key, thread);
    String oldHash = Credentials.hash(sid), newHash = Credentials.hash(candidate);
    Session saved =
        required(
            store.restart(
                new AuthorityStore.RestartSession(
                    oldHash,
                    key,
                    thread,
                    newHash,
                    clock.millis(),
                    settings.idleTimeout().toMillis())));
    if (!"ACTIVE".equals(saved.state())
        || saved.expiresAt() <= clock.millis()
        || !thread.equals(saved.threadId())
        || saved.guestSubject() != null) throw GatewayException.unavailable();
    channel(saved.channel());
    access.require(saved);
    String output;
    if (newHash.equals(saved.idHash())) output = candidate;
    else if (oldHash.equals(saved.idHash())) output = sid;
    else throw GatewayException.unavailable();
    cookies.issue(response, output);
    headers(response, saved);
    return saved;
  }

  /** 유효성 검사와 활동 연장을 최종 저장소에서 원자 처리하고 만료 헤더를 반영한다. 동일 활동 키는 추가 연장하지 않는다. */
  public Session activity(HttpServletResponse response, Session s, String key) {
    Session updated = touch(s, key);
    headers(response, updated);
    return updated;
  }

  /** Idempotency-Key 헤더가 하나이고 허용된 형식인지 확인한다. 재시도 시 호출자는 같은 키를 유지해야 한다. */
  public static String key(HttpServletRequest e) {
    var keys = Collections.list(e.getHeaders("Idempotency-Key"));
    if (keys.size() != 1) throw GatewayException.badRequest();
    return Credentials.identifier(keys.getFirst());
  }

  /** 프론트의 만료 안내에 필요한 서버 시각·세션 만료 시각을 밀리초로 제공하고 인증 응답 캐시를 금지한다. */
  private void headers(HttpServletResponse response, Session s) {
    response.setHeader("X-Chat-Auth-Expires-At", Long.toString(s.expiresAt()));
    response.setHeader("X-Server-Time", Long.toString(clock.millis()));
    response.setHeader("Cache-Control", "no-store");
  }

  /** 필수 저장 결과가 없으면 연동 오류로 거절한다. */
  private static Session required(Session session) {
    if (session == null) throw GatewayException.badGateway();
    return session;
  }
}
