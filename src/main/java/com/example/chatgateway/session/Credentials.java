package com.example.chatgateway.session;

import com.example.chatgateway.exception.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Credentials {
  private static final SecureRandom RANDOM = new SecureRandom();

  /** 정적 자격증명 유틸리티의 인스턴스 생성을 막는다. */
  private Credentials() {}

  /** CSPRNG 32바이트를 padding 없는 base64url로 인코딩해 세션·실행 자격증명 등에 사용한다. */
  public static String random() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** 응답 유실 재시도에도 같은 SID를 재현한다. 이전 SID를 HMAC 비밀키로 사용하며 원문을 저장하지 않는다. */
  public static String restartSid(String sid, String key, String threadId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(opaque(sid)), "HmacSHA256"));
      String message = "session-restart:" + key.length() + ":" + key + ":" + threadId;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 원문 대신 저장하거나 동일 입력을 대조할 SHA-256/base64url 값을 계산한다. */
  public static String hash(String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 32바이트 base64url 자격증명의 길이·문자 형식을 검사한다. 실제 존재·권한·만료는 저장소에서 별도로 확인한다. */
  public static String opaque(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw GatewayException.unauthorized();
    return value;
  }

  /** 헤더·경로·업무 키에 사용할 식별자의 길이와 허용 문자를 검사한다. */
  public static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}"))
      throw GatewayException.badRequest();
    return value;
  }

  /** 비어 있지 않은 설정값과 전달값을 MessageDigest.isEqual로 비교한다. */
  public static boolean matches(String expected, String actual) {
    return expected != null
        && !expected.isBlank()
        && actual != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
