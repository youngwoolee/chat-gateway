package com.example.chatgateway.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.session.Credentials;
import com.example.chatgateway.session.Session;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ClickService {
  private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
  private final SessionAuthentication auth;
  private final ObjectMapper mapper;
  private final Clock clock;

  /** 세션 인증과 JSON 로그 직렬화, 서버 수신 시각을 주입한다. */
  public ClickService(SessionAuthentication auth, ObjectMapper mapper, Clock clock) {
    this.auth = auth;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 검증된 클릭만 세션 활동으로 반영하고 JSON 로그로 기록한다. 재전송 로그는 key로 식별한다. */
  public void record(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (request.getContentLengthLong() > 1048576)
      throw new GatewayException(413, "RESOURCE_EXHAUSTED");
    byte[] body = request.getInputStream().readNBytes(1048577);
    if (body.length > 1048576) throw new GatewayException(413, "RESOURCE_EXHAUSTED");
    JsonNode json = parse(body);
    String thread = Credentials.identifier(json.path("threadId").textValue());
    if (request.getHeader("X-Mfa-Result") != null) throw GatewayException.badRequest();
    String key = SessionAuthentication.key(request);
    var session = auth.inspect(request, response);
    if (!session.threadId().equals(thread)) throw GatewayException.conflict();
    ObjectNode event = auditEvent(json, session, key);
    auth.activity(response, session, "click:" + key);
    AUDIT.info("click log request: {}", kv("message", event));
  }

  /** 기존 수집기의 message 형식을 유지한다. sessionId는 인증 쿠키가 아닌 대화 ID다. */
  private ObjectNode auditEvent(JsonNode input, Session session, String key) {
    ObjectNode event = mapper.createObjectNode();
    event.put("chatbot", "new-chatbot");
    event.put("name", "click");
    event.put("sessionId", session.threadId());
    event.put("channelId", session.channel());
    event.put("userId", session.member() ? session.subject() : null);
    event.put("login", session.member());
    ObjectNode button = null;
    if (input.hasNonNull("buttonInfo")) {
      button = mapper.createObjectNode();
      for (String field : BUTTON_FIELDS)
        button.set(
            field,
            input.path("buttonInfo").path(field).isMissingNode()
                ? mapper.nullNode()
                : input.path("buttonInfo").get(field));
    }
    event.set("buttonInfo", button == null ? mapper.nullNode() : button);
    // 이탈 분석용 신규 정보는 기존 message 안에 추가한다. 임의 최상위 입력은 기록하지 않는다.
    event.put("event", "chatbot_click");
    event.put("receivedAt", clock.millis());
    event.put("key", key);
    event.put("threadId", session.threadId());
    event.put("userType", session.userType());
    for (String field : new String[] {"scenarioId", "state", "component"})
      event.set(field, input.get(field));
    return event;
  }

  private static final String[] BUTTON_FIELDS = {
    "buttonType", "buttonNm", "buttonUrl", "buttonUtterance", "phoneNumber"
  };

  /** 크기가 제한된 JSON의 중복 키·후행 데이터와 필수 클릭 필드를 검사한다. */
  private JsonNode parse(byte[] body) {
    try {
      var json =
          mapper
              .reader()
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(body);
      if (json == null || !json.isObject()) throw GatewayException.badRequest();
      for (String field : new String[] {"threadId", "scenarioId", "state"})
        if (!json.path(field).isTextual() || json.path(field).asText().isBlank())
          throw GatewayException.badRequest();
      var component = json.path("component");
      if (!(component.isObject() || component.isTextual())
          || (component.isTextual() && component.asText().isBlank()))
        throw GatewayException.badRequest();
      JsonNode button = json.get("buttonInfo");
      if (button != null && !button.isNull()) {
        if (!button.isObject()) throw GatewayException.badRequest();
        for (String field : BUTTON_FIELDS) {
          JsonNode value = button.get(field);
          if (value != null && !value.isNull() && !value.isTextual())
            throw GatewayException.badRequest();
        }
      }
      return json;
    } catch (IOException error) {
      throw GatewayException.badRequest();
    }
  }
}
