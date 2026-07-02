package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.Issue;
import com.company.issueops.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecurrenceInsightServiceTest {

  @Mock
  private IssueRepository issues;

  @Mock
  private AiClient aiClient;

  private RecurrenceInsightService service;

  private RecurrenceInsightService service() {
    return new RecurrenceInsightService(
      issues,
      aiClient,
      new ObjectMapper(),
      new DataScopeService()
    );
  }

  /** 校验层必须：剔除指向不存在 issueNo 的关联、捕获证据里夹带的伪造编号、并据此强制转人工。 */
  @Test
  void verificationStripsHallucinatedReferencesAndForcesHumanReview() {
    Issue target = issue(3L, "PBI-20260603-0003", "批量导入用户偶发失败");
    target.setReopened(true);
    Issue real = issue(1L, "PBI-20260601-0001", "支付成功后订单状态延迟更新");
    when(issues.findAll()).thenReturn(List.of(target, real));

    when(aiClient.available()).thenReturn(true);
    when(aiClient.provider()).thenReturn("deepseek");
    when(aiClient.model()).thenReturn("deepseek-v4-pro");
    // 模型输出：一个真实关联 + 一个伪造关联；证据里夹带一个不存在的编号；自报无需复核
    when(aiClient.chatJson(any(), any())).thenReturn(
      Optional.of(
        Map.of(
          "recurrenceSummary",
          "异步改造未覆盖消息积压场景",
          "rootCauseHypotheses",
          List.of(
            Map.of(
              "hypothesis",
              "异步任务消费能力不足，消息积压导致超时",
              "confidence",
              0.8,
              "evidence",
              List.of(
                "PBI-20260601-0001 根因：消息消费积压",
                "PBI-99999999-9999 同类历史问题" // 伪造编号，证据里夹带
              )
            )
          ),
          "whyPreviousFixFailed",
          "压测未模拟消息积压与并发",
          "correlatedIssues",
          List.of(
            Map.of("issueNo", "PBI-20260601-0001", "relation", "根因同为消息积压"),
            Map.of("issueNo", "PBI-FAKE-0000", "relation", "凭空捏造的关联") // 伪造关联
          ),
          "systemicFix",
          List.of("扩容消费者并分片导入"),
          "verifyPlan",
          List.of("生产并发回放验证"),
          "needHumanReview",
          false // 模型自报无需复核，校验层应推翻它
        )
      )
    );

    service = service();
    Map<String, Object> result = service.analyzeOne(3L);

    assertThat(result.get("issueNo")).isEqualTo("PBI-20260603-0003");
    assertThat(result.get("analysisMode")).isEqualTo("ai");

    // 伪造的关联问题必须被剔除，只保留真实存在的
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> correlated = (List<Map<String, Object>>) result.get(
      "correlatedIssues"
    );
    assertThat(correlated).hasSize(1);
    assertThat(correlated.get(0).get("issueNo")).isEqualTo("PBI-20260601-0001");

    @SuppressWarnings("unchecked")
    Map<String, Object> grounding = (Map<String, Object>) result.get("groundingReport");
    @SuppressWarnings("unchecked")
    List<String> droppedCorrelations = (List<String>) grounding.get("droppedCorrelations");
    @SuppressWarnings("unchecked")
    List<String> fabricatedReferences = (List<String>) grounding.get("fabricatedReferences");
    assertThat(droppedCorrelations).containsExactly("PBI-FAKE-0000");
    assertThat(fabricatedReferences).containsExactly("PBI-99999999-9999");

    // 证据里夹带伪造编号 -> 该假设不算 grounded，且整体强制转人工（即便模型自报 false）
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hypotheses = (List<Map<String, Object>>) result.get(
      "rootCauseHypotheses"
    );
    assertThat(hypotheses).hasSize(1);
    assertThat(hypotheses.get(0).get("grounded")).isEqualTo(false);
    assertThat(result.get("needHumanReview")).isEqualTo(true);
  }

  /** 模型不可用时，必须诚实兜底：不伪造分析，回原始证据并标注 evidence-only。 */
  @Test
  void fallsBackToEvidenceOnlyWhenAiUnavailable() {
    Issue target = issue(3L, "PBI-20260603-0003", "批量导入用户偶发失败");
    target.setReopened(true);
    when(issues.findAll()).thenReturn(List.of(target));
    when(aiClient.available()).thenReturn(false);

    service = service();
    Map<String, Object> result = service.analyzeOne(3L);

    assertThat(result.get("analysisMode")).isEqualTo("evidence-only");
    assertThat(result.get("generatedBy")).isEqualTo("local-rules");
    assertThat(result.get("needHumanReview")).isEqualTo(true);
    assertThat((List<?>) result.get("rootCauseHypotheses")).isEmpty();
    assertThat(result).containsKey("evidence"); // 原始证据被透出，供人工归纳
  }

  private Issue issue(Long id, String issueNo, String title) {
    Issue issue = new Issue();
    issue.setId(id);
    issue.setIssueNo(issueNo);
    issue.setTitle(title);
    issue.setDeleted(false);
    issue.setReopened(false);
    return issue;
  }
}
