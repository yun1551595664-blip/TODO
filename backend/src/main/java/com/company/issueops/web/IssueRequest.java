package com.company.issueops.web;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public record IssueRequest(
  @NotBlank(message = "问题标题不能为空") String title,
  String description,
  String source,
  String businessScene,
  String issueType,
  String impactScope,
  String customerImpact,
  String reproduceSteps,
  String priority,
  String status,
  String responsibleDepartment,
  String responsiblePerson,
  String tapdUrl,
  String attachmentUrl,
  String rootCause,
  String fixSolution,
  String verifyResult,
  Boolean reopened,
  String reopenedReason,
  LocalDateTime expectedFinishTime,
  LocalDateTime actualFinishTime,
  String createdBy
) {}
