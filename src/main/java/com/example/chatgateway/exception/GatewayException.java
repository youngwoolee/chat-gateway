package com.example.chatgateway.exception;

public final class GatewayException extends RuntimeException {
  private final int code;
  private final String status;

  /** 인증 원문이나 내부 상세 메시지는 포함하지 않는다. */
  public GatewayException(int code, String status) {
    super(status);
    this.code = code;
    this.status = status;
  }

  /** 응답에 사용할 HTTP 상태 코드를 반환한다. */
  public int code() {
    return code;
  }

  /** 프론트가 분기할 오류 상태 문자열을 반환한다. */
  public String status() {
    return status;
  }

  /** 세션·플랫폼 인증 실패를 나타내는 401 예외를 생성한다. */
  public static GatewayException unauthorized() {
    return new GatewayException(401, "UNAUTHENTICATED");
  }

  /** 최종 저장소 또는 필수 연동을 사용할 수 없을 때의 503 예외를 생성한다. */
  public static GatewayException unavailable() {
    return new GatewayException(503, "UNAVAILABLE");
  }

  /** 멱등 요청이나 상태 전이가 충돌했음을 나타내는 409 예외를 생성한다. */
  public static GatewayException conflict() {
    return new GatewayException(409, "CONFLICT");
  }

  /** 채널·소유권·서비스 권한 부족을 나타내는 403 예외를 생성한다. */
  public static GatewayException denied() {
    return new GatewayException(403, "PERMISSION_DENIED");
  }

  /** 필수값 누락이나 입력 형식 오류를 나타내는 400 예외를 생성한다. */
  public static GatewayException badRequest() {
    return new GatewayException(400, "INVALID_ARGUMENT");
  }

  /** 외부 연동 실패나 잘못된 확인 응답을 나타내는 502 예외를 생성한다. */
  public static GatewayException badGateway() {
    return new GatewayException(502, "BAD_GATEWAY");
  }

  /** 업무·고객·인증 요청 결합 불일치를 나타내는 403 예외를 생성한다. */
  public static GatewayException mfaInvalid() {
    return new GatewayException(403, "MFA_INVALID");
  }
}
