package com.example.chatgateway.controller;

import com.example.chatgateway.client.search.AutocompleteClient;
import com.example.chatgateway.client.search.AutocompleteClient.Query;
import com.example.chatgateway.service.SessionAuthentication;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutocompleteController {
  private final SessionAuthentication auth;
  private final AutocompleteClient search;

  /** 세션 검증과 자동완성 서비스 호출을 주입한다. */
  public AutocompleteController(SessionAuthentication auth, AutocompleteClient search) {
    this.auth = auth;
    this.search = search;
  }

  /** 조회 전용 POST: 게스트/회원 허용, 활동 연장·_O 인증·키 소비 없이 기존 응답 포맷을 반환한다. */
  @PostMapping(value = "/api/chat/auto", consumes = "application/json")
  public Result autocomplete(
      HttpServletRequest request, HttpServletResponse response, @RequestBody Query query) {
    auth.inspect(request, response);
    return new Result("SUCCESS", "성공", search.search(query));
  }

  public record Result(String code, String message, JsonNode data) {}
}
