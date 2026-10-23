package com.example.chatgateway.session;

import com.example.chatgateway.exception.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

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

  /** 게스트 ID 발급은 세션 서비스 책임이며 G/W는 합의된 접두사·길이·헤더 안전성만 확인한다. */
  public static boolean isGuestId(String value) {
    return value != null
        && value.length() == 13
        && value.startsWith("G")
        && value.chars().allMatch(c -> c >= 33 && c <= 126);
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
}
