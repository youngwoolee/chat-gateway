package com.example.chatgateway.controller;

import com.example.chatgateway.exception.GatewayException;
import com.example.chatgateway.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 일반 HTTP와 SSE 요청 모두 동일한 인증 서비스를 거쳐 Agent B/E로 전달한다. */
@RestController
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }

  /** 발화·도구 요청. 응답 유형은 B/E의 Content-Type으로 결정한다. */
  @PostMapping("/api/chat")
  public SseEmitter chat(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    return service.chat(request, response);
  }

  /** 도구 실행은 발화 JSON 검증과 구분하여 전달한다. */
  @PostMapping("/api/chat/threads/{threadId}/tools/{toolName}/{toolRoute}")
  public SseEmitter tool(
      HttpServletRequest request, HttpServletResponse response, @PathVariable String threadId)
      throws Exception {
    return service.tool(request, response, threadId);
  }

  /** 이력·아티팩트 조회는 세션을 연장하지 않는다. */
  @GetMapping({
    "/api/chat/threads",
    "/api/chat/threads/{threadId}/messages",
    "/api/chat/threads/{threadId}/artifacts/{artifactId}"
  })
  public void history(
      HttpServletRequest request,
      HttpServletResponse response,
      @PathVariable(required = false) String threadId)
      throws Exception {
    service.history(request, response, threadId);
  }

  /** 명시적으로 공개한 경로·메서드 외에는 자동 OPTIONS/중계로 확장하지 않는다. */
  @RequestMapping(
      path = {
        "/api/chat",
        "/api/chat/auto",
        "/api/chat/threads",
        "/api/chat/threads/{threadId}/messages",
        "/api/chat/threads/{threadId}/artifacts/{artifactId}",
        "/api/chat/threads/{threadId}/tools/{toolName}/{toolRoute}"
      },
      method = {RequestMethod.OPTIONS, RequestMethod.HEAD})
  public void methodNotAllowed() {
    throw new GatewayException(405, "METHOD_NOT_ALLOWED");
  }
}
