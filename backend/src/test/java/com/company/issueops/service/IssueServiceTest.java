package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.repository.IssueLogRepository;
import com.company.issueops.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueServiceTest {

  @Mock
  private IssueRepository issues;

  @Mock
  private IssueLogRepository logs;

  @Mock
  private AiClient aiClient;

  @Mock
  private ObjectMapper objectMapper;

  @Mock
  private ApplicationEventPublisher events;

  @InjectMocks
  private IssueService service;

  @Test
  void statusChangeCompletesIssueAndWritesLog() {
    Issue issue = issue(1L, "处理中");
    when(issues.findById(1L)).thenReturn(Optional.of(issue));
    when(issues.save(any(Issue.class))).thenAnswer(invocation ->
      invocation.getArgument(0)
    );
    when(logs.save(any(IssueLog.class))).thenAnswer(invocation ->
      invocation.getArgument(0)
    );

    Issue result = service.status(1L, "已完成", "测试员", null);

    assertThat(result.getStatus()).isEqualTo("已完成");
    assertThat(result.getActualFinishTime()).isNotNull();
    ArgumentCaptor<IssueLog> captor = ArgumentCaptor.forClass(IssueLog.class);
    verify(logs).save(captor.capture());
    assertThat(captor.getValue().getContent()).isEqualTo("处理中 → 已完成");
    assertThat(captor.getValue().getOperator()).isEqualTo("测试员");
  }

  @Test
  void dashboardContainsFocusedMetricsIncludingRiskCounters() {
    Issue pending = issue(1L, "待处理");
    pending.setCreatedAt(LocalDateTime.now());
    pending.setUpdatedAt(LocalDateTime.now().minusDays(2));
    pending.setExpectedFinishTime(LocalDateTime.now().minusDays(1));
    pending.setReopened(true);
    Issue completed = issue(2L, "已完成");
    completed.setCreatedAt(LocalDateTime.now());
    completed.setUpdatedAt(LocalDateTime.now().minusDays(1));
    completed.setActualFinishTime(LocalDateTime.now());
    Issue legacy = issue(3L, null);
    legacy.setDeleted(null);
    legacy.setCreatedAt(LocalDateTime.now());
    when(issues.findAll()).thenReturn(List.of(pending, completed, legacy));

    Map<String, Object> result = service.dashboard();

    assertThat(result).containsKeys(
      "total",
      "pending",
      "processing",
      "verifying",
      "completed",
      "reopened",
      "overdue",
      "updatedAt",
      "dataUpdatedAt",
      "monthlyNew",
      "monthlyCompleted"
    );
    assertThat(result).doesNotContainKeys(
      "fixed",
      "monthlyClosed",
      "suspended",
      "completionRate",
      "trend"
    );
    assertThat(result.get("total")).isEqualTo(3L);
    assertThat(result.get("pending")).isEqualTo(2L);
    assertThat(result.get("completed")).isEqualTo(1L);
    assertThat(result.get("reopened")).isEqualTo(1L);
    assertThat(result.get("overdue")).isEqualTo(1L);
    assertThat(result.get("updatedAt")).isNotEqualTo(result.get("dataUpdatedAt"));
    assertThat((String) result.get("updatedAt")).endsWith("+08:00");
  }

  @Test
  void dashboardSupportsTrendRangeFilter() {
    when(issues.findAll()).thenReturn(List.of());

    assertThat(service.dashboardTrend("12w")).hasSize(12);
    assertThat(service.dashboardTrend("30d")).hasSize(30);
    assertThat(service.dashboardTrend("unknown")).hasSize(8);
  }

  @Test
  void dashboardAiInsightReturnsActionableSections() {
    Issue issue = issue(1L, "处理中");
    issue.setTitle("支付成功后订单状态延迟更新");
    issue.setIssueType("系统缺陷");
    issue.setResponsibleDepartment("交易中心");
    issue.setPriority("P1");
    issue.setCreatedAt(LocalDateTime.now().minusDays(2));
    issue.setExpectedFinishTime(LocalDateTime.now().minusDays(1));
    when(issues.findAll()).thenReturn(List.of(issue));

    Map<String, Object> result = service.dashboardAiInsight();

    assertThat(result).containsKeys(
      "summaryText",
      "riskLevel",
      "rootClusters",
      "actions",
      "signals",
      "promptSuggestions"
    );
    assertThat((List<?>) result.get("rootClusters")).hasSize(1);
    assertThat((List<?>) result.get("actions")).isNotEmpty();
    assertThat(result.get("riskLevel")).isEqualTo("高");
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportAnalysisReturnsPeriodAggregation() {
    Issue overdue = issue(1L, "处理中");
    overdue.setTitle("支付状态延迟");
    overdue.setIssueType("系统缺陷");
    overdue.setPriority("P1");
    overdue.setCreatedAt(LocalDateTime.of(2026, 6, 3, 9, 0));
    overdue.setExpectedFinishTime(LocalDateTime.of(2026, 6, 10, 18, 0));

    Issue completed = issue(2L, "已完成");
    completed.setTitle("报表字段缺失");
    completed.setIssueType("报表问题");
    completed.setPriority("P2");
    completed.setCreatedAt(LocalDateTime.of(2026, 6, 5, 9, 0));
    completed.setUpdatedAt(LocalDateTime.of(2026, 6, 8, 18, 0));
    completed.setActualFinishTime(LocalDateTime.of(2026, 6, 8, 18, 0));

    Issue carryOver = issue(3L, "已完成");
    carryOver.setTitle("五月遗留问题");
    carryOver.setIssueType("系统缺陷");
    carryOver.setPriority("P0");
    carryOver.setCreatedAt(LocalDateTime.of(2026, 5, 25, 9, 0));
    carryOver.setUpdatedAt(LocalDateTime.of(2026, 6, 15, 18, 0));
    carryOver.setActualFinishTime(LocalDateTime.of(2026, 6, 15, 18, 0));

    Issue previous = issue(4L, "已完成");
    previous.setTitle("上期问题");
    previous.setIssueType("历史问题");
    previous.setCreatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
    previous.setUpdatedAt(LocalDateTime.of(2026, 5, 7, 18, 0));
    previous.setActualFinishTime(LocalDateTime.of(2026, 5, 7, 18, 0));

    when(issues.findAll()).thenReturn(List.of(overdue, completed, carryOver, previous));

    Map<String, Object> result = service.reportAnalysis(
      null,
      "2026-06-01",
      "2026-06-30"
    );

    Map<String, Object> period = (Map<String, Object>) result.get("period");
    assertThat(period.get("label")).isEqualTo("2026-06-01 至 2026-06-30");
    assertThat((List<?>) result.get("trend")).hasSize(30);
    assertThat(result).containsKeys(
      "periodSummary",
      "structureMatrix",
      "priorityEfficiency",
      "datasets",
      "events"
    );

    List<Map<String, Object>> periodSummary = (List<Map<String, Object>>) result.get(
      "periodSummary"
    );
    assertThat(periodSummary.get(0).get("value")).isEqualTo(2L);
    assertThat(periodSummary.get(1).get("value")).isEqualTo(2L);
    assertThat(periodSummary.get(2).get("value")).isEqualTo(1L);
    assertThat(periodSummary.get(3).get("value")).isEqualTo(1L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportAnalysisRecomputesAggregatesByDepartmentFilter() {
    Issue tech = issue(1L, "处理中");
    tech.setTitle("支付状态延迟");
    tech.setIssueType("系统缺陷");
    tech.setResponsibleDepartment("技术部");
    tech.setCreatedAt(LocalDateTime.of(2026, 6, 3, 9, 0));
    tech.setExpectedFinishTime(LocalDateTime.of(2026, 6, 5, 18, 0));

    Issue product = issue(2L, "已完成");
    product.setTitle("报表字段缺失");
    product.setIssueType("报表问题");
    product.setResponsibleDepartment("产品部");
    product.setCreatedAt(LocalDateTime.of(2026, 6, 4, 9, 0));
    product.setActualFinishTime(LocalDateTime.of(2026, 6, 6, 18, 0));

    Issue unassigned = issue(3L, "待处理");
    unassigned.setTitle("未分配问题");
    unassigned.setCreatedAt(LocalDateTime.of(2026, 6, 7, 9, 0));

    when(issues.findAll()).thenReturn(List.of(tech, product, unassigned));

    Map<String, Object> result = service.reportAnalysis(
      null,
      "2026-06-01",
      "2026-06-30",
      "技术部"
    );

    Map<String, Object> summary = (Map<String, Object>) result.get("summary");
    assertThat(summary.get("total")).isEqualTo(1L);
    assertThat(summary.get("overdue")).isEqualTo(1L);
    assertThat((List<String>) result.get("availableDepartments"))
      .containsExactly("产品部", "技术部", "未分配");
    assertThat((List<Issue>) result.get("issues"))
      .extracting(Issue::getResponsibleDepartment)
      .containsOnly("技术部");
  }

  @Test
  void rejectsRemovedSuspendedStatus() {
    assertThatThrownBy(() -> service.status(1L, "已挂起", "测试员", null))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("待处理、处理中、待验证、已完成");
  }

  @Test
  void rejectsInvalidStatusTransition() {
    Issue issue = issue(1L, "待处理");
    when(issues.findById(1L)).thenReturn(Optional.of(issue));

    assertThatThrownBy(() -> service.status(1L, "已完成", "测试员", null))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("非法状态流转：待处理 → 已完成");
  }

  @Test
  void reopeningCompletedIssueReturnsItToProcessing() {
    Issue issue = issue(1L, "已完成");
    issue.setActualFinishTime(LocalDateTime.now());
    when(issues.findById(1L)).thenReturn(Optional.of(issue));
    when(issues.save(any(Issue.class))).thenAnswer(invocation ->
      invocation.getArgument(0)
    );
    when(logs.save(any(IssueLog.class))).thenAnswer(invocation ->
      invocation.getArgument(0)
    );

    Issue result = service.reopened(1L, true, "生产环境再次出现", "客服");

    assertThat(result.getReopened()).isTrue();
    assertThat(result.getReopenedReason()).isEqualTo("生产环境再次出现");
    assertThat(result.getStatus()).isEqualTo("处理中");
    assertThat(result.getActualFinishTime()).isNull();
  }

  private Issue issue(Long id, String status) {
    Issue issue = new Issue();
    issue.setId(id);
    issue.setStatus(status);
    issue.setDeleted(false);
    issue.setReopened(false);
    return issue;
  }
}
