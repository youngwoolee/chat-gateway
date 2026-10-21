package com.example.chatgateway.controller;

import com.example.chatgateway.service.ClickService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClickController {
  private final ClickService clicks;

  /** 클릭 검증·세션 갱신·로그 기록 서비스를 주입한다. */
  public ClickController(ClickService clicks) {
    this.clicks = clicks;
  }

  /** Gateway에서 클릭 활동을 처리하며 B/E를 호출하지 않는다. */
  @PostMapping(value = "/api/click", consumes = "application/json")
  public Map<String, String> click(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    clicks.record(request, response);
    return Map.of("status", "OK");
  }
}
