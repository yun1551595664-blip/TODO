package com.company.issueops.service;

import com.company.issueops.domain.AuditLog;
import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.repository.AuditLogRepository;
import com.company.issueops.service.AuthService.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {

  public static final String SOURCE_MANUAL = "MANUAL";
  public static final String SOURCE_AI = "AI";

  private final AuditLogRepository auditLogs;
  private final ObjectMapper objectMapper;

  public List<AuditLog> listIssueAudits(Long issueId) {
    return auditLogs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
      "ISSUE",
      String.valueOf(issueId)
    );
  }

  public void recordIssueChange(
    AuthUser user,
    String actionType,
    String source,
    String aiActionId,
    Issue before,
    Issue after,
    HttpServletRequest request
  ) {
    Issue target = after != null ? after : before;
    record(
      user,
      actionType,
      "ISSUE",
      target == null || target.getId() == null ? null : String.valueOf(target.getId()),
      target == null ? null : target.getIssueNo(),
      source,
      aiActionId,
      issueSnapshot(before),
      issueSnapshot(after),
      request
    );
  }

  public void recordIssueSnapshotChange(
    AuthUser user,
    String actionType,
    String source,
    String aiActionId,
    Map<String, Object> before,
    Issue after,
    HttpServletRequest request
  ) {
    Map<String, Object> afterSnapshot = issueSnapshot(after);
    Object targetId = afterSnapshot != null ? afterSnapshot.get("id") : before.get("id");
    Object targetNo = afterSnapshot != null
      ? afterSnapshot.get("issueNo")
      : before.get("issueNo");
    record(
      user,
      actionType,
      "ISSUE",
      targetId == null ? null : String.valueOf(targetId),
      targetNo == null ? null : String.valueOf(targetNo),
      source,
      aiActionId,
      before,
      afterSnapshot,
      request
    );
  }

  public void recordIssueLog(
    AuthUser user,
    Issue issue,
    IssueLog log,
    HttpServletRequest request
  ) {
    record(
      user,
      "ADD_ISSUE_LOG",
      "ISSUE",
      issue == null || issue.getId() == null ? null : String.valueOf(issue.getId()),
      issue == null ? null : issue.getIssueNo(),
      SOURCE_MANUAL,
      null,
      null,
      logSnapshot(log),
      request
    );
  }

  public void recordAiAction(
    AuthUser user,
    String actionId,
    Map<String, Object> requestBody,
    Map<String, Object> result,
    HttpServletRequest request
  ) {
    Issue issue = result == null || !(result.get("issue") instanceof Issue value)
      ? null
      : value;
    record(
      user,
      "AI_ACTION_EXECUTE",
      issue == null ? "AI_ACTION" : "ISSUE",
      issue == null || issue.getId() == null ? actionId : String.valueOf(issue.getId()),
      issue == null ? null : issue.getIssueNo(),
      SOURCE_AI,
      actionId,
      requestBody,
      aiResultSnapshot(result),
      request
    );
  }

  public Map<String, Object> issueSnapshot(Issue issue) {
    if (issue == null) return null;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", issue.getId());
    data.put("issueNo", issue.getIssueNo());
    data.put("title", issue.getTitle());
    data.put("source", issue.getSource());
    data.put("businessScene", issue.getBusinessScene());
    data.put("issueType", issue.getIssueType());
    data.put("impactScope", issue.getImpactScope());
    data.put("priority", issue.getPriority());
    data.put("status", issue.getStatus());
    data.put("responsibleDepartment", issue.getResponsibleDepartment());
    data.put("responsiblePerson", issue.getResponsiblePerson());
    data.put("reopened", issue.getReopened());
    data.put("reopenedReason", issue.getReopenedReason());
    data.put("expectedFinishTime", issue.getExpectedFinishTime());
    data.put("actualFinishTime", issue.getActualFinishTime());
    data.put("createdBy", issue.getCreatedBy());
    data.put("createdAt", issue.getCreatedAt());
    data.put("updatedAt", issue.getUpdatedAt());
    data.put("deleted", issue.getDeleted());
    return data;
  }

  private void record(
    AuthUser user,
    String actionType,
    String targetType,
    String targetId,
    String targetNo,
    String source,
    String aiActionId,
    Object beforeData,
    Object afterData,
    HttpServletRequest request
  ) {
    AuditLog auditLog = new AuditLog();
    auditLog.setOperatorName(user == null ? "system" : user.displayName());
    auditLog.setOperatorRole(user == null ? "SYSTEM" : user.role());
    auditLog.setActionType(actionType);
    auditLog.setTargetType(targetType);
    auditLog.setTargetId(targetId);
    auditLog.setTargetNo(targetNo);
    auditLog.setSource(source);
    auditLog.setAiActionId(aiActionId);
    auditLog.setBeforeData(toJson(beforeData));
    auditLog.setAfterData(toJson(afterData));
    auditLog.setIp(clientIp(request));
    auditLog.setUserAgent(truncate(header(request, "User-Agent"), 500));
    auditLogs.save(auditLog);
  }

  private Map<String, Object> logSnapshot(IssueLog log) {
    if (log == null) return null;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", log.getId());
    data.put("actionType", log.getActionType());
    data.put("content", log.getContent());
    data.put("operator", log.getOperator());
    data.put("createdAt", log.getCreatedAt());
    return data;
  }

  private Map<String, Object> aiResultSnapshot(Map<String, Object> result) {
    if (result == null) return null;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("executed", result.get("executed"));
    data.put("actionType", result.get("actionType"));
    data.put("message", result.get("message"));
    data.put("logId", result.get("logId"));
    data.put("executedAt", result.get("executedAt"));
    if (result.get("issue") instanceof Issue issue) {
      data.put("issue", issueSnapshot(issue));
    }
    return data;
  }

  private String toJson(Object data) {
    if (data == null) return null;
    try {
      return objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      return String.valueOf(data);
    }
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = header(request, "X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return truncate(forwardedFor.split(",")[0].trim(), 80);
    }
    return request == null ? null : truncate(request.getRemoteAddr(), 80);
  }

  private String header(HttpServletRequest request, String name) {
    return request == null ? null : request.getHeader(name);
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) return value;
    return value.substring(0, maxLength);
  }
}
