package com.example.chatgateway.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import com.example.chatgateway.config.ChatProperties;
import com.example.chatgateway.config.StagingProperties;
import com.example.chatgateway.session.Session;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class ChatGatewayRoutes {
  /** 채팅·이력·도구 요청은 Controller 없이 공식 Gateway HTTP handler로 중계한다. */
  @Bean
  RouterFunction<ServerResponse> chatRoutes(ChatProperties properties, StagingProperties staging) {
    return route("agent-chat")
        .route(request -> allowed(request.servletRequest()), http())
        .before(
            request -> {
              Session session =
                  (Session) request.servletRequest().getAttribute(ChatGatewayFilter.SESSION);
              String target =
                  staging
                      .channels()
                      .getOrDefault(session.channel(), properties.backend().baseUrl());
              return uri(target).apply(request);
            })
        .before(rewritePath("/api/chat(?<remaining>.*)", "/internal/v1/chat${remaining}"))
        .build();
  }

  /** 공개할 경로와 메서드를 인증 필터와 라우트가 함께 사용한다. */
  public static boolean allowed(HttpServletRequest request) {
    // 커밋 후 오류의 INCLUDE/ERROR 디스패치를 새 B/E 요청으로 중계하지 않는다.
    if (request.getDispatcherType() != DispatcherType.REQUEST) return false;
    String path = request.getRequestURI();
    return switch (request.getMethod()) {
      case "POST" ->
          path.equals("/api/chat") || path.matches("/api/chat/threads/[^/]+/tools/[^/]+");
      case "GET" ->
          path.equals("/api/chat/threads")
              || path.matches("/api/chat/threads/[^/]+/(messages|artifacts/[^/]+)");
      default -> false;
    };
  }
}
