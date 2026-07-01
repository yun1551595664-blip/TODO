package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.AiActionService;
import com.company.issueops.service.AiInsightService;
import com.company.issueops.service.AuditLogService;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai-insights")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiInsightController {

  private final AiInsightService service;
  private final AiActionService actionService;
  private final AuditLogService auditLogService;

  @GetMapping("/overview")
  ApiResponse<Map<String, Object>> overview() {
    return ApiResponse.ok(service.overview());
  }

  @PostMapping("/refresh")
  ApiResponse<Map<String, Object>> refresh() {
    return ApiResponse.ok(service.refresh());
  }

  @GetMapping("/ai-analysis")
  ApiResponse<Map<String, Object>> aiAnalysis() {
    return ApiResponse.ok(service.aiAnalysis());
  }

  @PostMapping("/chat")
  ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(
      service.chat(
        String.valueOf(body.getOrDefault("question", "")),
        String.valueOf(body.getOrDefault("insightId", "")),
        body
      )
    );
  }

  @PostMapping("/sessions")
  ApiResponse<Map<String, Object>> createSession(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.createSession(body));
  }

  @GetMapping("/sessions/{sessionId}/messages")
  ApiResponse<List<Map<String, Object>>> sessionMessages(@PathVariable String sessionId) {
    return ApiResponse.ok(service.sessionMessages(sessionId));
  }

  @PostMapping(
    value = "/sessions/{sessionId}/chat/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  SseEmitter streamChat(
    @PathVariable String sessionId,
    @RequestBody Map<String, Object> body
  ) {
    return service.streamChat(sessionId, body);
  }

  @PostMapping("/actions/execute")
  @Transactional
  ApiResponse<Map<String, Object>> executeAction(
    @RequestBody Map<String, Object> body,
    HttpServletRequest request
  ) {
    Map<String, Object> result = actionService.execute(body);
    auditLogService.recordAiAction(
      currentUser(request),
      Objects.toString(body.get("actionId"), null),
      body,
      result,
      request
    );
    return ApiResponse.ok(result);
  }

  private AuthUser currentUser(HttpServletRequest request) {
    Object value = request.getAttribute(AuthService.REQUEST_USER_ATTRIBUTE);
    return value instanceof AuthUser user ? user : null;
  }
}
