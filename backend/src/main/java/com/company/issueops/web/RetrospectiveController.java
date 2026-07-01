package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.RetrospectiveService;
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
  ApiResponse<Map<String, Object>> overview() {
    return ApiResponse.ok(service.overview());
  }

  @GetMapping("/ai-suggestion")
  ApiResponse<Map<String, Object>> aiSuggestion() {
    return ApiResponse.ok(service.aiSuggestion());
  }

  @PostMapping("/draft")
  ApiResponse<Map<String, Object>> draft(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.draft(body));
  }
}
