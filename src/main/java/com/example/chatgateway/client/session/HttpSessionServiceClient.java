package com.example.chatgateway.client.session;

import com.example.chatgateway.config.SessionServiceProperties;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthRequest;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** 세션 서비스 API 호출만 담당한다. 저장·만료·동시 처리 보장은 agent-session-service의 책임이다. */
public final class HttpSessionServiceClient implements AuthorityStore {
  private static final String API = "/internal/v1/authority/";
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final Map<String, Integer> BUSINESS_ERRORS =
      Map.of(
          "UNAUTHENTICATED",
          401,
          "PERMISSION_DENIED",
          403,
          "MFA_REQUIRED",
          403,
          "MFA_INVALID",
          403,
          "MFA_EXPIRED",
          403,
          "CONFLICT",
          409,
          "OPERATION_CHANGED",
          409);
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
        postWithoutAuthFailures(
            "find-session", new SessionLookup(hash), Session.class, "SESSION_NOT_FOUND");
    return result == null ? null : session(result, hash);
  }

  /** 생성할 세션과 키를 보내고 서버가 동일 신원을 저장했는지 확인한다. */
  @Override
  public Session create(Session proposed, String creationKey) {
    return savedSession(
        postWithoutAuthFailures(
            "create-session", new CreateSession(proposed, creationKey), Session.class),
        proposed);
  }

  /** 원자 만료 검사·활동 연장을 요청한다. */
  @Override
  public Session touch(String hash, long now, long ttl, String activityKey) {
    return session(
        postWithoutAuthFailures(
            "touch-session", new TouchSession(hash, now, ttl, activityKey), Session.class),
        hash);
  }

  /** 게스트 폐기와 회원 생성을 한 요청으로 전달한다. */
  @Override
  public Session replaceGuest(String hash, String key, Session replacement, long now) {
    return savedSession(
        postWithoutAuthFailures(
            "replace-guest", new ReplaceGuest(hash, key, replacement, now), Session.class),
        replacement);
  }

  /** 원자 restart 명령을 전송하고 최초 교체 또는 현재 쿠키의 재시도 결과인지 확인한다. */
  @Override
  public Session restart(RestartSession command) {
    Session result = postWithoutAuthFailures("restart-session", command, Session.class);
    session(result, result.idHash());
    require(
        (command.replacementHash().equals(result.idHash())
                || command.hash().equals(result.idHash()))
            && command.threadId().equals(result.threadId())
            && result.guestSubject() == null
            && "ACTIVE".equals(result.state())
            && result.expiresAt() > command.now());
    return result;
  }

  /** 인증 요청 등록용 문맥을 저장한다. */
  @Override
  public ExecutionGrant saveGrant(ExecutionGrant grant) {
    return grant(
        postWithoutAuthFailures("save-grant", new SaveGrant(grant), ExecutionGrant.class),
        grant.hash());
  }

  /** 문맥의 만료 판정은 세션 서비스에 위임한다. */
  @Override
  public ExecutionGrant grant(String hash, long now) {
    return grant(
        postWithoutAuthFailures("find-grant", new FindGrant(hash, now), ExecutionGrant.class),
        hash);
  }

  /** 같은 등록 key에 다른 입력이 결합되지 않았는지 확인한다. */
  @Override
  public AuthRequest registerAuth(AuthRequest request, long now) {
    AuthRequest result =
        authRequest(
            postWithoutAuthFailures(
                "register-auth-request", new RegisterAuth(request, now), AuthRequest.class));
    require(result.sameRegistration(request));
    return result;
  }

  /** 요청한 ID의 추가 인증 등록을 조회한다. */
  @Override
  public AuthRequest authRequest(String id) {
    AuthRequest result =
        authRequest(
            postWithoutAuthFailures("find-auth-request", new AuthLookup(id), AuthRequest.class));
    require(id.equals(result.authReqId()));
    return result;
  }

  /** SDK 입력을 최초 값으로 고정하는 원자 명령을 호출한다. */
  @Override
  public AuthRequest saveSdkInput(String id, String sdkInput, long now) {
    AuthRequest result =
        authRequest(
            postWithoutAuthFailures(
                "save-sdk-input", new SaveSdkInput(id, sdkInput, now), AuthRequest.class));
    require(id.equals(result.authReqId()));
    return result;
  }

  /** 명시적인 영수증 부재만 null로 반환한다. */
  @Override
  public ResumeReceipt findResume(String sessionHash, String key) {
    ResumeReceipt result =
        postWithoutAuthFailures(
            "find-resume",
            new ResumeLookup(sessionHash, key),
            ResumeReceipt.class,
            "RESUME_NOT_FOUND");
    return result == null ? null : receipt(result, sessionHash, key);
  }

  /** 기존 명령 DTO를 그대로 보내며 소비 결과·실패 ID를 제출 목록과 대조한다. */
  @Override
  public ResumeReceipt consumeAuth(ConsumeAuth command) {
    List<String> ids = command.proofs().stream().map(Proof::authReqId).toList();
    try {
      ResumeReceipt result =
          receipt(
              exchange("consume-auth-requests", command, ResumeReceipt.class, null),
              command.sessionHash(),
              command.key());
      require(
          command.fingerprint().equals(result.fingerprint()) && ids.equals(result.authReqIds()));
      return result;
    } catch (GatewayException error) {
      require(ids.containsAll(error.failedAuthReqIds()));
      throw error;
    }
  }

  private Session session(Session result, String hash) {
    requiredFields(
        result.idHash(),
        result.subject(),
        result.channel(),
        result.state(),
        result.userType(),
        result.threadId());
    require(hash.equals(result.idHash()) && !result.threadId().isBlank());
    return result;
  }

  private Session savedSession(Session result, Session proposed) {
    session(result, proposed.idHash());
    require(
        proposed.subject().equals(result.subject())
            && proposed.channel().equals(result.channel())
            && proposed.threadId().equals(result.threadId())
            && proposed.userType().equals(result.userType())
            && Objects.equals(proposed.guestSubject(), result.guestSubject()));
    return result;
  }

  private ExecutionGrant grant(ExecutionGrant result, String hash) {
    requiredFields(result.hash(), result.subject(), result.channel(), result.userType());
    require(hash.equals(result.hash()));
    return result;
  }

  private AuthRequest authRequest(AuthRequest result) {
    requiredFields(
        result.authReqId(),
        result.key(),
        result.subject(),
        result.channel(),
        result.threadId(),
        result.operationId(),
        result.version(),
        result.interfaceId(),
        result.actionHash(),
        result.comparison());
    return result;
  }

  private ResumeReceipt receipt(ResumeReceipt result, String sessionHash, String key) {
    requiredFields(result.sessionHash(), result.key(), result.fingerprint(), result.authReqIds());
    require(
        !result.authReqIds().isEmpty()
            && result.authReqIds().stream().noneMatch(Objects::isNull)
            && sessionHash.equals(result.sessionHash())
            && key.equals(result.key()));
    return result;
  }

  private <T> T postWithoutAuthFailures(String path, Object request, Class<T> type) {
    return postWithoutAuthFailures(path, request, type, null);
  }

  /** MFA 소비 외 응답에서는 항목별 인증 실패 정보를 노출하지 않는다. */
  private <T> T postWithoutAuthFailures(
      String path, Object request, Class<T> type, String absentStatus) {
    try {
      return exchange(path, request, type, absentStatus);
    } catch (GatewayException error) {
      throw new GatewayException(error.code(), error.status());
    }
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
    List<String> failed =
        code == 403 && error.failedAuthReqIds() != null ? error.failedAuthReqIds() : List.of();
    require(failed.stream().noneMatch(Objects::isNull));
    return new GatewayException(code, error.status(), failed);
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

  private record SaveGrant(ExecutionGrant grant) {}

  private record FindGrant(String hash, long now) {}

  private record RegisterAuth(AuthRequest request, long now) {}

  private record AuthLookup(String id) {}

  private record SaveSdkInput(String id, String sdkInput, long now) {}

  private record ResumeLookup(String sessionHash, String key) {}

  private record ErrorResponse(ServiceError error) {}

  private record ServiceError(
      int code, String status, String message, List<String> failedAuthReqIds) {}
}
