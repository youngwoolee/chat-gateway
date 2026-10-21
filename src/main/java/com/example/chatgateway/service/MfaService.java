package com.example.chatgateway.service;

import com.example.chatgateway.client.pfc.PfcClient;
import com.example.chatgateway.client.pfc.PfcClient.MfaResult;
import com.example.chatgateway.config.MfaProperties;
import com.example.chatgateway.config.SessionSettings;
import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.AuthRequest;
import com.example.chatgateway.session.AuthRequest.Comparison;
import com.example.chatgateway.session.AuthorityStore;
import com.example.chatgateway.session.AuthorityStore.ConsumeAuth;
import com.example.chatgateway.session.AuthorityStore.Proof;
import com.example.chatgateway.session.AuthorityStore.ResumeReceipt;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 업무 실행을 하지 않고 인증 등록과 resume의 일괄 인증 소비만 담당한다. */
@Service
public class MfaService {
  private final AuthorityStore store;
  private final PfcClient platform;
  private final SessionSettings settings;
  private final MfaProperties policy;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ChannelAccessService access;

  public MfaService(
      AuthorityStore store,
      PfcClient platform,
      SessionSettings settings,
      MfaProperties policy,
      ObjectMapper mapper,
      Clock clock,
      ChannelAccessService access) {
    this.store = store;
    this.platform = platform;
    this.settings = settings;
    this.policy = policy;
    this.mapper = mapper;
    this.clock = clock;
    this.access = access;
  }

  /** key 범위에 입력을 고정한다. SDK 시작·입력 저장 실패는 같은 key로 재개할 수 있다. */
  public Registered register(String context, Registration input) {
    input.validate();
    if (!settings.allowedInterfaces().contains(input.interfaceId()))
      throw GatewayException.denied();
    var grant =
        required(store.grant(Credentials.hash(Credentials.opaque(context)), clock.millis()));
    grant.requireMemberThread();
    access.require(grant.channel(), grant.subject(), true);
    var request =
        required(
            store.registerAuth(
                new AuthRequest(
                    "ar_" + Credentials.random(),
                    input.key(),
                    grant.subject(),
                    grant.channel(),
                    grant.threadId(),
                    input.operationId(),
                    input.version(),
                    input.interfaceId(),
                    input.actionHash(),
                    input.comparison(),
                    clock.millis() + policy.registrationTtl().toMillis(),
                    null),
                clock.millis()));
    if (request.sdkInput() == null) {
      String sdk = required(platform.start(request));
      request = required(store.saveSdkInput(request.authReqId(), sdk, clock.millis()));
    }
    return new Registered(request.authReqId(), request.sdkInput(), request.expiresAt());
  }

  /** 검증 성공 목록만 원자 소비한다. 동일 요청 재전달은 최초 소비 영수증을 사용한다. */
  public List<String> verifyResume(
      Session session,
      String thread,
      String key,
      String target,
      byte[] body,
      List<MfaResult> results) {
    if (results.isEmpty()) return List.of();
    if (!session.member()) throw GatewayException.mfaInvalid();
    String fingerprint = fingerprint(target, body, results);
    var previous = store.findResume(session.idHash(), key);
    if (previous != null) {
      receipt(previous, session, key, fingerprint, results);
      var retries =
          results.stream()
              .map(
                  r ->
                      new Proof(
                          r.authReqId(),
                          Credentials.hash(r.method() + ":" + r.tranID()),
                          previous.expiresAt()))
              .toList();
      return receipt(
          required(
              store.consumeAuth(
                  new ConsumeAuth(
                      session.idHash(), key, fingerprint, thread, retries, clock.millis()))),
          session,
          key,
          fingerprint,
          results);
    }
    var proofs = new ArrayList<Proof>();
    var failed = new ArrayList<String>();
    boolean hiddenFailure = false;
    for (var result : results) {
      AuthRequest request;
      try {
        request = required(store.authRequest(result.authReqId()));
      } catch (GatewayException e) {
        if (e.code() != 403) throw e;
        hiddenFailure = true;
        continue;
      }
      if (!session.subject().equals(request.subject())
          || !session.channel().equals(request.channel())
          || !thread.equals(request.threadId())) {
        hiddenFailure = true;
        continue;
      }
      try {
        if (request.expiresAt() <= clock.millis()) throw GatewayException.mfaExpired();
        if (!policy.methods().contains(result.method())) throw GatewayException.mfaInvalid();
        var verified = required(platform.verify(result, request));
        if (!session.subject().equals(verified.subject())
            || !request.authReqId().equals(verified.authReqId())
            || !result.tranID().equals(verified.tranID())
            || !Objects.equals(result.txId(), verified.txId())
            || !result.method().equals(verified.method())
            || !request.comparison().equals(verified.comparison()))
          throw GatewayException.mfaInvalid();
        if (verified.expiresAt() <= clock.millis()) throw GatewayException.mfaExpired();
        proofs.add(
            new Proof(
                result.authReqId(),
                Credentials.hash(result.method() + ":" + result.tranID()),
                Math.min(verified.expiresAt(), clock.millis() + policy.verifiedTtl().toMillis())));
      } catch (GatewayException e) {
        if (e.code() != 403) throw e;
        failed.add(result.authReqId());
      }
    }
    if (hiddenFailure || !failed.isEmpty())
      throw GatewayException.mfaFailure("MFA_INVALID", failed);
    var consumed =
        required(
            store.consumeAuth(
                new ConsumeAuth(
                    session.idHash(),
                    key,
                    fingerprint,
                    thread,
                    List.copyOf(proofs),
                    clock.millis())));
    return receipt(consumed, session, key, fingerprint, results);
  }

  /** 영수증을 다른 요청·다른 세션에 적용하지 않으며 기한을 연장하지 않는다. */
  private List<String> receipt(
      ResumeReceipt receipt,
      Session session,
      String key,
      String fingerprint,
      List<MfaResult> results) {
    if (!session.idHash().equals(receipt.sessionHash()) || !key.equals(receipt.key()))
      throw GatewayException.unavailable();
    if (!fingerprint.equals(receipt.fingerprint())) throw GatewayException.conflict();
    var ids = results.stream().map(MfaResult::authReqId).toList();
    if (!ids.equals(receipt.authReqIds())) throw GatewayException.unavailable();
    if (receipt.expiresAt() <= clock.millis()) throw GatewayException.mfaExpired();
    return ids;
  }

  /** 원문 본문·목적지·인증 목록을 묶어 변경된 resume의 재사용을 차단한다. */
  private String fingerprint(String target, byte[] body, List<MfaResult> results) {
    try {
      return Credentials.hash(
          mapper.writeValueAsString(
              List.of(target, Base64.getEncoder().encodeToString(body), results)));
    } catch (Exception e) {
      throw GatewayException.badRequest();
    }
  }

  public record Registration(
      String key,
      String operationId,
      String version,
      String interfaceId,
      String actionHash,
      String ssn,
      String iPin,
      String code,
      String usage,
      String content) {
    private void validate() {
      Credentials.identifier(key);
      Credentials.identifier(operationId);
      Credentials.identifier(version);
      Credentials.identifier(interfaceId);
      if (actionHash == null || !actionHash.matches("[A-Za-z0-9_-]{43}"))
        throw GatewayException.badRequest();
      if ((ssn == null || ssn.isBlank()) && (iPin == null || iPin.isBlank()))
        throw GatewayException.badRequest();
      for (String value : new String[] {ssn, iPin, code, usage, content})
        if (value != null && (value.isBlank() || value.length() > 2048))
          throw GatewayException.badRequest();
      if (code == null || usage == null || content == null) throw GatewayException.badRequest();
    }

    private Comparison comparison() {
      return new Comparison(ssn, iPin, code, usage, content);
    }

    @Override
    public String toString() {
      return "Registration[redacted]";
    }
  }

  public record Registered(String authReqId, String sdkInput, long expiresAt) {}

  private static <T> T required(T value) {
    if (value == null) throw GatewayException.badGateway();
    return value;
  }
}
