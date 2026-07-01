package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.Issue;
import com.company.issueops.repository.IssueRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiActionServiceTest {

  @Mock
  private IssueService issueService;

  @Mock
  private IssueRepository issues;

  private AiActionService service;

  @BeforeEach
  void setUp() {
    service = new AiActionService(issueService, issues);
  }

  @Test
  void executeRejectsClientSuppliedRawAction() {
    Map<String, Object> forgedAction = Map.of(
      "action",
      Map.of(
        "actionType",
        "UPDATE_STATUS",
        "payload",
        Map.of("issueId", 1L, "status", "已完成")
      )
    );

    assertThatThrownBy(() -> service.execute(forgedAction))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("待确认操作 ID");
  }

  @Test
  void pendingActionExecutesOnceByServerIssuedActionId() {
    Issue issue = issue(1L, "PBI-20260601-0001", "处理中");
    Issue updated = issue(1L, "PBI-20260601-0001", "待验证");
    when(issues.findById(1L)).thenReturn(Optional.of(issue));
    when(issueService.status(eq(1L), eq("待验证"), eq("AI 助理"), any()))
      .thenReturn(updated);

    Map<String, Object> normalized = service
      .normalizePendingAction(
        Map.of(
          "actionType",
          "UPDATE_STATUS",
          "payload",
          Map.of("issueId", 1L, "status", "待验证")
        )
      )
      .orElseThrow();
    Map<String, Object> pending = service
      .registerPendingAction(normalized, "AI-20260625-0001")
      .orElseThrow();

    String actionId = String.valueOf(pending.get("actionId"));
    Map<String, Object> result = service.execute(Map.of("actionId", actionId));

    assertThat(result.get("executed")).isEqualTo(true);
    assertThat(result.get("actionType")).isEqualTo("UPDATE_STATUS");
    verify(issueService).status(eq(1L), eq("待验证"), eq("AI 助理"), any());
    assertThatThrownBy(() -> service.execute(Map.of("actionId", actionId)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("不存在或已过期");
  }

  @Test
  void createIssueActionRequiresMinimumQualityFields() {
    Optional<Map<String, Object>> action = service.normalizePendingAction(
      Map.of("actionType", "CREATE_ISSUE", "payload", Map.of("title", "只有标题"))
    );

    assertThat(action).isEmpty();
  }

  private Issue issue(Long id, String issueNo, String status) {
    Issue issue = new Issue();
    issue.setId(id);
    issue.setIssueNo(issueNo);
    issue.setTitle("支付成功后订单状态延迟更新");
    issue.setStatus(status);
    issue.setDeleted(false);
    return issue;
  }
}
