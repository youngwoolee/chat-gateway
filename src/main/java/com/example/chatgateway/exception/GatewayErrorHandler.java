package com.example.chatgateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestController
@RestControllerAdvice
public class GatewayErrorHandler implements ErrorController {
  private final ObjectMapper mapper;

  /** 공통 오류 직렬화기를 주입한다. */
  public GatewayErrorHandler(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 커밋 이후 예외는 컨테이너로 전파해 정상 종료 청크나 JSON을 덧붙이지 않는다. */
  @ExceptionHandler(Exception.class)
  public void handleRequest(
      HttpServletRequest request, HttpServletResponse response, Exception error) throws Exception {
    handle(response, error, request.getRequestURI().startsWith("/api/"));
  }

  /** 채팅 필터에서 발생한 GW 오류만 프론트 규격으로 변환한다. B/E 응답은 이 메서드를 거치지 않는다. */
  public void handle(HttpServletResponse response, Exception error) throws Exception {
    handle(response, error, true);
  }

  private void handle(HttpServletResponse response, Exception error, boolean frontend)
      throws Exception {
    if (response.isCommitted()) throw error;
    while ((error instanceof ServletException
            || error instanceof ResourceAccessException
            || error instanceof UncheckedIOException)
        && error.getCause() instanceof Exception cause) error = cause;
    int code =
        error instanceof GatewayException g
            ? g.code()
            : error instanceof HttpMessageNotReadableException
                ? 400
                : error instanceof ErrorResponse e
                    ? e.getStatusCode().value()
                    : error instanceof SocketTimeoutException
                        ? 504
                        : error instanceof IOException ? 502 : 500;
    if (frontend) code = frontendCode(code);
    write(
        response,
        code,
        !frontend && error instanceof GatewayException g ? g.status() : status(code),
        error instanceof GatewayException g ? g.failedAuthReqIds() : List.of());
  }

  /** 서블릿 오류 디스패치도 동일한 빈 메시지 JSON으로 통일한다. */
  @RequestMapping("/error")
  public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (response.isCommitted()) return;
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = code instanceof Integer value ? value : 404;
    Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    if (original instanceof String uri && uri.startsWith("/api/")) status = frontendCode(status);
    write(response, status, status(status), List.of());
  }

  /** 기존 오류 코드와 캐시 금지 정책을 직렬화한다. */
  private void write(HttpServletResponse response, int code, String status, List<String> failedIds)
      throws IOException {
    response.setStatus(code);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    var detail = new LinkedHashMap<String, Object>();
    detail.put("code", code);
    detail.put("status", status);
    detail.put("message", "");
    if (!failedIds.isEmpty()) detail.put("failedAuthReqIds", failedIds);
    mapper.writeValue(response.getOutputStream(), Map.of("error", detail));
  }

  /** 요청 오류와 의존 서비스 장애를 프론트가 처리할 최소 코드로 묶는다. */
  private int frontendCode(int code) {
    return switch (code) {
      case 409, 413, 415 -> 400;
      case 502, 504 -> 503;
      default -> code;
    };
  }

  /** 기존 HTTP 상태별 공개 오류 이름을 유지한다. */
  private String status(int code) {
    return switch (code) {
      case 400 -> "INVALID_ARGUMENT";
      case 401 -> "UNAUTHENTICATED";
      case 403 -> "PERMISSION_DENIED";
      case 404 -> "NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 413 -> "RESOURCE_EXHAUSTED";
      case 502 -> "BAD_GATEWAY";
      case 503 -> "UNAVAILABLE";
      case 504 -> "DEADLINE_EXCEEDED";
      default -> "INTERNAL";
    };
  }
}
