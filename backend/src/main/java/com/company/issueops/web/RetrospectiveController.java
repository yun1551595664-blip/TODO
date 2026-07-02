package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AuthUser;
import com.company.issueops.service.RetrospectiveService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retrospectives")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RetrospectiveController {

  private final RetrospectiveService service;

  @GetMapping("/overview")
  ApiResponse<Map<String, Object>> overview(HttpServletRequest request) {
    return ApiResponse.ok(service.overview(currentUser(request)));
  }

  @GetMapping("/ai-suggestion")
  ApiResponse<Map<String, Object>> aiSuggestion(HttpServletRequest request) {
    return ApiResponse.ok(service.aiSuggestion(currentUser(request)));
  }

  @PostMapping("/draft")
  ApiResponse<Map<String, Object>> draft(
    @RequestBody Map<String, Object> body,
    HttpServletRequest request
  ) {
    return ApiResponse.ok(service.draft(currentUser(request), body));
  }

  private AuthUser currentUser(HttpServletRequest request) {
    Object value = request.getAttribute(AuthService.REQUEST_USER_ATTRIBUTE);
    return value instanceof AuthUser user ? user : null;
  }
}
