package com.example.chatgateway.client.session;

import com.example.chatgateway.config.SessionServiceProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** 세션 서비스 API 호출만 담당한다. 저장·만료·동시 처리 보장은 agent-session-service의 책임이다. */
public final class HttpSessionServiceClient implements AuthorityStore {
  private static final String API = "/internal/v1/authority/";
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final Map<String, Integer> BUSINESS_ERRORS =
      Map.of("UNAUTHENTICATED", 401, "PERMISSION_DENIED", 403, "CONFLICT", 409);
  private final RestClient client;
  private final SessionServiceProperties properties;
  private final ObjectMapper mapper;

  public HttpSessionServiceClient(
      RestClient client, SessionServiceProperties properties, ObjectMapper mapper) {
    this.client = client;
    this.properties = properties;
    this.mapper = mapper;
  }

  /** 외부 인증 티켓을 소비하기 전에 서비스 준비 상태를 확인한다. */
  @Override
  public void checkAvailable() {
    require(properties.credential() != null && !properties.credential().isBlank());
    try {
      client
          .get()
          .uri(API + "ready")
          .headers(headers -> headers.setBearerAuth(properties.credential()))
          .exchange(
              (request, response) -> {
                require(response.getStatusCode().value() == 204);
                return null;
              });
    } catch (Exception error) {
      throw GatewayException.unavailable();
    }
  }

  /** 지정된 세션의 명시적 부재만 null로 반환한다. */
  @Override
  public Session find(String hash) {
    Session result =
        exchange("find-session", new SessionLookup(hash), Session.class, "SESSION_NOT_FOUND");
    return result == null ? null : session(result, hash);
  }

  /** 게스트는 subject=null로 발급을 요청하고 회원은 PFC 신원의 보존을 확인한다. */
  @Override
  public Session create(Session proposed, String creationKey) {
    return savedSession(
        exchange("create-session", new CreateSession(proposed, creationKey), Session.class),
        proposed);
  }

  /** 원자 만료 검사·활동 연장을 요청한다. */
  @Override
  public Session touch(String hash, long now, long ttl, String activityKey) {
    return session(
        exchange("touch-session", new TouchSession(hash, now, ttl, activityKey), Session.class),
        hash);
  }

  /** 게스트 폐기와 회원 생성을 한 요청으로 전달한다. */
  @Override
  public Session replaceGuest(String hash, String key, Session replacement, long now) {
    return savedSession(
        exchange("replace-guest", new ReplaceGuest(hash, key, replacement, now), Session.class),
        replacement);
  }

  private Session session(Session result, String hash) {
    requiredFields(
        result.idHash(), result.subject(), result.channel(), result.state(), result.userType());
    require(hash.equals(result.idHash()) && !hash.isBlank());
    require(
        ("MEMBER".equals(result.userType()) && result.subject().matches("P[0-9]{9}"))
            || ("GUEST".equals(result.userType()) && Credentials.isGuestId(result.subject())));
    return result;
  }

  private Session savedSession(Session result, Session proposed) {
    session(result, proposed.idHash());
    require(
        (("GUEST".equals(proposed.userType()) && proposed.subject() == null)
                ? Credentials.isGuestId(result.subject())
                : Objects.equals(proposed.subject(), result.subject()))
            && proposed.channel().equals(result.channel())
            && proposed.userType().equals(result.userType())
            && Objects.equals(proposed.guestSubject(), result.guestSubject()));
    return result;
  }

  private <T> T exchange(String path, Object request, Class<T> type) {
    return exchange(path, request, type, null);
  }

  /** 공통 처리는 HTTP·크기 제한·역직렬화·오류 변환으로 제한한다. */
  private <T> T exchange(String path, Object body, Class<T> type, String absentStatus) {
    require(properties.credential() != null && !properties.credential().isBlank());
    try {
      var request =
          client
              .post()
              .uri(API + path)
              .headers(headers -> headers.setBearerAuth(properties.credential()))
              .contentType(MediaType.APPLICATION_JSON)
              .body(body);
      return request.exchange(
          (outgoing, response) -> {
            int code = response.getStatusCode().value();
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            require(bytes.length <= MAX_RESPONSE_BYTES);
            if (code != 200) {
              GatewayException error = serviceError(bytes, code, absentStatus);
              if (error == null) return null;
              throw error;
            }
            T result =
                mapper
                    .readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(bytes);
            require(result != null);
            return result;
          });
    } catch (GatewayException error) {
      throw error;
    } catch (Exception error) {
      // 자격증명·응답 본문을 오류에 노출하지 않는다.
      throw GatewayException.unavailable();
    }
  }

  /** 내부 오류 계약만 해석한다. 프론트 응답 코드 변환은 GatewayErrorHandler가 담당한다. */
  private GatewayException serviceError(byte[] bytes, int code, String absentStatus)
      throws IOException {
    ServiceError error = mapper.readValue(bytes, ErrorResponse.class).error();
    require(error != null && error.code() == code);
    if (code == 404 && absentStatus != null && absentStatus.equals(error.status())) return null;
    require(error.status() != null && BUSINESS_ERRORS.getOrDefault(error.status(), 0) == code);
    return new GatewayException(code, error.status());
  }

  private static void requiredFields(Object... values) {
    for (Object value : values) require(value != null);
  }

  private static void require(boolean valid) {
    if (!valid) throw GatewayException.unavailable();
  }

  private record SessionLookup(String hash) {}

  private record CreateSession(Session session, String creationKey) {}

  private record TouchSession(String hash, long now, long ttl, String activityKey) {}

  private record ReplaceGuest(String hash, String key, Session replacement, long now) {}

  private record ErrorResponse(ServiceError error) {}

  private record ServiceError(int code, String status, String message) {}
}
