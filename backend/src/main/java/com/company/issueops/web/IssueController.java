package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.domain.*;
import com.company.issueops.service.IssueAiService;
import com.company.issueops.service.IssueService;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IssueController {

  private final IssueService service;
  private final IssueAiService issueAiService;

  @GetMapping("/issues")
  ApiResponse<Page<Issue>> list(
    @RequestParam Map<String, String> q,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size
  ) {
    return ApiResponse.ok(
      service.list(
        q,
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
      )
    );
  }

  @GetMapping("/issues/{id}")
  ApiResponse<Issue> get(@PathVariable Long id) {
    return ApiResponse.ok(service.get(id));
  }

  @PostMapping("/issues")
  ApiResponse<Issue> create(@Valid @RequestBody IssueRequest r) {
    return ApiResponse.ok(service.create(r));
  }

  @PutMapping("/issues/{id}")
  ApiResponse<Issue> update(
    @PathVariable Long id,
    @Valid @RequestBody IssueRequest r
  ) {
    return ApiResponse.ok(service.update(id, r));
  }

  @DeleteMapping("/issues/{id}")
  ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.ok();
  }

  @PatchMapping("/issues/{id}/status")
  ApiResponse<Issue> status(
    @PathVariable Long id,
    @RequestBody Map<String, String> b
  ) {
    return ApiResponse.ok(
      service.status(id, b.get("status"), b.get("operator"), b.get("content"))
    );
  }

  @PatchMapping("/issues/{id}/reopened")
  ApiResponse<Issue> reopened(
    @PathVariable Long id,
    @RequestBody Map<String, Object> body
  ) {
    boolean reopened = Boolean.TRUE.equals(body.get("reopened"));
    return ApiResponse.ok(
      service.reopened(
        id,
        reopened,
        Objects.toString(body.get("reason"), null),
        Objects.toString(body.get("operator"), null)
      )
    );
  }

  @PostMapping("/issues/{id}/logs")
  ApiResponse<IssueLog> log(
    @PathVariable Long id,
    @RequestBody Map<String, String> b
  ) {
    return ApiResponse.ok(
      service.addLog(
        service.get(id),
        b.getOrDefault("actionType", "处理记录"),
        b.get("content"),
        b.get("operator")
      )
    );
  }

  @GetMapping("/dashboard/statistics")
  ApiResponse<Map<String, Object>> dashboard() {
    return ApiResponse.ok(service.dashboardStatistics());
  }

  @GetMapping("/dashboard/trend")
  ApiResponse<List<Map<String, Object>>> dashboardTrend(
    @RequestParam(defaultValue = "8w") String range
  ) {
    return ApiResponse.ok(service.dashboardTrend(range));
  }

  @GetMapping("/dashboard/ai-insight")
  ApiResponse<Map<String, Object>> dashboardAiInsight() {
    return ApiResponse.ok(service.dashboardAiInsight());
  }

  @PostMapping("/dashboard/ai-insight/query")
  ApiResponse<Map<String, Object>> dashboardAiInsightQuery(
    @RequestBody Map<String, String> body
  ) {
    return ApiResponse.ok(service.dashboardAiQuery(body.get("question")));
  }

  @GetMapping({ "/reports/overview", "/retrospective" })
  ApiResponse<Map<String, Object>> report() {
    return ApiResponse.ok(service.report());
  }

  @PostMapping("/issues/{id}/ai/{type}")
  ApiResponse<Map<String, Object>> ai(
    @PathVariable Long id,
    @PathVariable String type
  ) {
    return ApiResponse.ok(issueAiService.analyze(id, type));
  }
}
