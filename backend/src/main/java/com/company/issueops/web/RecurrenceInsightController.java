package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.RecurrenceInsightService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-insights/recurrence")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RecurrenceInsightController {

  private final RecurrenceInsightService service;

  /** 全部复发问题的根因归纳。 */
  @GetMapping
  ApiResponse<Map<String, Object>> all() {
    return ApiResponse.ok(service.analyzeAll());
  }

  /** 单个问题的根因归纳。 */
  @GetMapping("/{id}")
  ApiResponse<Map<String, Object>> one(@PathVariable Long id) {
    return ApiResponse.ok(service.analyzeOne(id));
  }
}
