package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.domain.AuditLog;
import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.service.AuditLogService;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AuthUser;
import com.company.issueops.service.IssueAiService;
import com.company.issueops.service.IssueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IssueController {

  private final IssueService service;
  private final IssueAiService issueAiService;
  private final AuditLogService auditLogService;

  @GetMapping("/issues")
  ApiResponse<Page<Issue>> list(
    @RequestParam Map<String, String> q,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    return ApiResponse.ok(
      service.list(
        user,
        q,
        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
      )
    );
  }

  @GetMapping("/issues/{id}")
  ApiResponse<Issue> get(@PathVariable Long id, HttpServletRequest request) {
    return ApiResponse.ok(service.get(currentUser(request), id));
  }

  @GetMapping("/issues/{id}/audits")
  ApiResponse<List<AuditLog>> audits(@PathVariable Long id, HttpServletRequest request) {
    service.get(currentUser(request), id);
    return ApiResponse.ok(auditLogService.listIssueAudits(id));
  }

  @PostMapping("/issues")
  @Transactional
  ApiResponse<Issue> create(
    @Valid @RequestBody IssueRequest requestBody,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    Issue result = service.create(withCreatedBy(requestBody, operatorName(user)));
    auditLogService.recordIssueChange(
      user,
      "CREATE_ISSUE",
      AuditLogService.SOURCE_MANUAL,
      null,
      null,
      result,
      request
    );
    return ApiResponse.ok(result);
  }

  @PutMapping("/issues/{id}")
  @Transactional
  ApiResponse<Issue> update(
    @PathVariable Long id,
    @Valid @RequestBody IssueRequest requestBody,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    Map<String, Object> before = auditLogService.issueSnapshot(service.get(user, id));
    Issue result = service.update(user, id, requestBody);
    auditLogService.recordIssueSnapshotChange(
      user,
      "UPDATE_ISSUE",
      AuditLogService.SOURCE_MANUAL,
      null,
      before,
      result,
      request
    );
    return ApiResponse.ok(result);
  }

  @DeleteMapping("/issues/{id}")
  @Transactional
  ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
    AuthUser user = currentUser(request);
    Map<String, Object> before = auditLogService.issueSnapshot(service.get(user, id));
    service.delete(user, id);
    auditLogService.recordIssueSnapshotChange(
      user,
      "DELETE_ISSUE",
      AuditLogService.SOURCE_MANUAL,
      null,
      before,
      null,
      request
    );
    return ApiResponse.ok();
  }

  @PatchMapping("/issues/{id}/status")
  @Transactional
  ApiResponse<Issue> status(
    @PathVariable Long id,
    @RequestBody Map<String, String> body,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    Map<String, Object> before = auditLogService.issueSnapshot(service.get(user, id));
    Issue result = service.status(
      user,
      id,
      body.get("status"),
      operatorName(user),
      body.get("content")
    );
    auditLogService.recordIssueSnapshotChange(
      user,
      "CHANGE_STATUS",
      AuditLogService.SOURCE_MANUAL,
      null,
      before,
      result,
      request
    );
    return ApiResponse.ok(result);
  }

  @PatchMapping("/issues/{id}/reopened")
  @Transactional
  ApiResponse<Issue> reopened(
    @PathVariable Long id,
    @RequestBody Map<String, Object> body,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    boolean reopened = Boolean.TRUE.equals(body.get("reopened"));
    Map<String, Object> before = auditLogService.issueSnapshot(service.get(user, id));
    Issue result = service.reopened(
      user,
      id,
      reopened,
      Objects.toString(body.get("reason"), null),
      operatorName(user)
    );
    auditLogService.recordIssueSnapshotChange(
      user,
      reopened ? "MARK_REOPENED" : "CLEAR_REOPENED",
      AuditLogService.SOURCE_MANUAL,
      null,
      before,
      result,
      request
    );
    return ApiResponse.ok(result);
  }

  @PostMapping("/issues/{id}/logs")
  @Transactional
  ApiResponse<IssueLog> log(
    @PathVariable Long id,
    @RequestBody Map<String, String> body,
    HttpServletRequest request
  ) {
    AuthUser user = currentUser(request);
    Issue issue = service.get(user, id);
    IssueLog result = service.addLog(
      issue,
      body.getOrDefault("actionType", "处理记录"),
      body.get("content"),
      operatorName(user)
    );
    auditLogService.recordIssueLog(user, issue, result, request);
    return ApiResponse.ok(result);
  }

  @GetMapping("/dashboard/statistics")
  ApiResponse<Map<String, Object>> dashboard(HttpServletRequest request) {
    return ApiResponse.ok(service.dashboardStatistics(currentUser(request)));
  }

  @GetMapping("/dashboard/trend")
  ApiResponse<List<Map<String, Object>>> dashboardTrend(
    @RequestParam(defaultValue = "8w") String range,
    HttpServletRequest request
  ) {
    return ApiResponse.ok(service.dashboardTrend(currentUser(request), range));
  }

  @GetMapping("/dashboard/ai-insight")
  ApiResponse<Map<String, Object>> dashboardAiInsight(HttpServletRequest request) {
    return ApiResponse.ok(service.dashboardAiInsight(currentUser(request)));
  }

  @PostMapping("/dashboard/ai-insight/query")
  ApiResponse<Map<String, Object>> dashboardAiInsightQuery(
    @RequestBody Map<String, String> body,
    HttpServletRequest request
  ) {
    return ApiResponse.ok(service.dashboardAiQuery(currentUser(request), body.get("question")));
  }

  @GetMapping({ "/reports/overview", "/retrospective" })
  ApiResponse<Map<String, Object>> report(HttpServletRequest request) {
    return ApiResponse.ok(service.report(currentUser(request)));
  }

  @PostMapping("/issues/{id}/ai/{type}")
  ApiResponse<Map<String, Object>> ai(
    @PathVariable Long id,
    @PathVariable String type,
    HttpServletRequest request
  ) {
    return ApiResponse.ok(issueAiService.analyze(currentUser(request), id, type));
  }

  private AuthUser currentUser(HttpServletRequest request) {
    Object value = request.getAttribute(AuthService.REQUEST_USER_ATTRIBUTE);
    return value instanceof AuthUser user ? user : null;
  }

  private String operatorName(AuthUser user) {
    return user == null ? "system" : user.displayName();
  }

  private IssueRequest withCreatedBy(IssueRequest request, String createdBy) {
    return new IssueRequest(
      request.title(),
      request.description(),
      request.source(),
      request.businessScene(),
      request.issueType(),
      request.impactScope(),
      request.customerImpact(),
      request.reproduceSteps(),
      request.priority(),
      request.status(),
      request.responsibleDepartment(),
      request.responsiblePerson(),
      request.tapdUrl(),
      request.attachmentUrl(),
      request.rootCause(),
      request.fixSolution(),
      request.verifyResult(),
      request.reopened(),
      request.reopenedReason(),
      request.expectedFinishTime(),
      request.actualFinishTime(),
      createdBy
    );
  }
}
