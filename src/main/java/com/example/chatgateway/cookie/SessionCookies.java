package com.example.chatgateway.cookie;

import com.example.chatgateway.client.pfc.PfcClient.IdentityCookies;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Credentials;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookies {
  private static final String SYSTEM_CODE_O = "PlatformSystemCode_O";
  private static final String AUTHENTICATION_O = "PlatformAuthentication_O";
  private static final String VERIFY_O = "PlatformVerify_O";
  private static final List<String> EXTERNAL = List.of(SYSTEM_CODE_O, AUTHENTICATION_O, VERIFY_O);
  private final SessionSettings settings;

  /** 플랫폼 공유 쿠키의 Domain·Path 설정을 주입한다. */
  public SessionCookies(SessionSettings settings) {
    this.settings = settings;
  }

  /** 채널 UUID의 첫 구간으로 쿠키를 구분한다. 허용 채널의 접두사는 서로 달라야 한다. */
  public static String name(String channel) {
    String prefix = channel.split("-", 2)[0];
    if (!prefix.matches("[A-Za-z0-9]+"))
      throw new IllegalArgumentException("Invalid channel cookie prefix");
    return "__Host-sh-session_" + prefix;
  }

  /** 쿠키가 없으면 null을 반환하고 같은 이름이 여러 번 전달되면 인증 오류로 거절한다. */
  private String value(HttpServletRequest e, String name) {
    var matches = Arrays.stream(values(e)).filter(c -> name.equals(c.getName())).toList();
    if (matches.isEmpty()) return null;
    if (matches.size() != 1) throw GatewayException.unauthorized();
    return matches.getFirst().getValue();
  }

  /** 세션 쿠키의 존재와 opaque ID 형식만 검사한다. 저장소 유효성 검사는 인증 서비스가 수행한다. */
  public String session(HttpServletRequest e, String channel) {
    String s = value(e, name(channel));
    return s == null ? null : Credentials.opaque(s);
  }

  /** 외부 인증이 필요한 분기에서 _O 3종을 파싱한다. 내부 코드 CBA는 제외하고 일부 누락·잘못된 값은 인증 오류로 거절한다. */
  public IdentityCookies identity(HttpServletRequest e) {
    if (EXTERNAL.stream()
        .noneMatch(n -> Arrays.stream(values(e)).anyMatch(c -> n.equals(c.getName())))) return null;
    String system = value(e, SYSTEM_CODE_O);
    if ("CBA".equals(system)) return null;
    String auth = value(e, AUTHENTICATION_O), verify = value(e, VERIFY_O);
    for (String v : new String[] {system, auth, verify})
      if (v == null || v.isBlank() || v.length() > 4096 || !v.matches("[!-~]+"))
        throw GatewayException.unauthorized();
    return new IdentityCookies(system, auth, verify);
  }

  /**
   * HttpOnly·Secure·SameSite=Lax·Path=/의 세션 쿠키를 발급한다. __Host- 규칙에 따라 Domain을 넣지 않으며 브라우저 세션 쿠키로
   * 유지한다.
   */
  public void issue(HttpServletResponse e, String sid, String channel) {
    e.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name(channel), sid)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .build()
            .toString());
  }

  public void clear(HttpServletResponse response, String channel) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name(channel), "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ZERO)
            .build()
            .toString());
  }

  public boolean hasIdentity(HttpServletRequest request) {
    return Arrays.stream(values(request)).anyMatch(c -> EXTERNAL.contains(c.getName()));
  }

  public String mfaState(HttpServletRequest request) {
    String state;
    try {
      state = value(request, "__state_as_id");
    } catch (GatewayException invalid) {
      throw GatewayException.badRequest();
    }
    if (state == null || state.isBlank() || state.length() > 4096 || !state.matches("[!-~]+"))
      throw GatewayException.badRequest();
    return state;
  }

  /** 플랫폼 계약에 맞춘 공유 쿠키 빌더를 구성한다. 생성·삭제 모두 동일 Domain·Path를 사용해야 한다. */
  private ResponseCookie.ResponseCookieBuilder platform(String name, String value) {
    return ResponseCookie.from(name, value)
        .domain(settings.platformDomain())
        .path(settings.platformPath())
        .secure(true)
        .httpOnly(true)
        .sameSite("Lax");
  }

  /** 인증에 실제 사용한 _O 3종을 브라우저에서 삭제한다. 서버 측 티켓 재사용 차단은 플랫폼 계약에서 별도로 보장해야 한다. */
  public void consume(HttpServletResponse e) {
    EXTERNAL.forEach(
        n -> e.addHeader("Set-Cookie", platform(n, "").maxAge(Duration.ZERO).build().toString()));
  }

  /** 홈페이지가 로그인 세션을 구성하는 데 사용할 _I 3종을 응답에 설정한다. */
  public void homepage(HttpServletResponse e, IdentityCookies c) {
    e.addHeader("Set-Cookie", platform("PlatformSystemCode_I", c.systemCode()).build().toString());
    e.addHeader(
        "Set-Cookie", platform("PlatformAuthentication_I", c.authentication()).build().toString());
    e.addHeader("Set-Cookie", platform("PlatformVerify_I", c.verify()).build().toString());
  }

  /** 같은 이름의 중복 쿠키를 보존해 인증 경계에서 검사한다. */
  private static Cookie[] values(HttpServletRequest request) {
    return request.getCookies() == null ? new Cookie[0] : request.getCookies();
  }
}
