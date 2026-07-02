package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.repository.IssueRepository;
import com.company.issueops.service.AuthService.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 复发根因归纳：规则引擎做不了的"真洞察"竖切。
 *
 * <p>三层结构，每一层职责清晰：
 *
 * <ol>
 *   <li>证据层（{@link #buildEvidence}）：纯代码组装结构化证据——问题全字段、处理时间线、
 *       其他在库问题摘要。不做任何推断，只"摆事实"。
 *   <li>推理层（{@link AiClient#chatJson}）：把证据交给大模型，归纳根因假设、解释为什么上次
 *       修复没挡住复发、判断可关联的同源问题。允许产出新结论。
 *   <li>校验层（{@link #verify}）：把模型的结论与真实数据对账——剔除引用了不存在 issueNo 的
 *       关联、夹带的伪造编号，置信度不足或发现伪造引用时强制 needHumanReview。
 * </ol>
 *
 * <p>模型不可用或未返回有效结果时，返回 {@code evidence-only} 兜底：只展示原始证据并明确标注
 * "AI 分析未生效"，绝不伪造分析结论。
 */
@Service
@RequiredArgsConstructor
public class RecurrenceInsightService {

  /** 最高置信度低于此阈值时，强制转人工复核。 */
  private static final double CONFIDENCE_REVIEW_THRESHOLD = 0.5;

  /** 形如 PBI-20260603-0003 的 issueNo 令牌，用于在自由文本证据中识别（并校验）被引用的编号。 */
  private static final Pattern ISSUE_NO_TOKEN = Pattern.compile(
    "[A-Za-z]{2,}-[0-9]{2,}(?:-[0-9]{2,})+"
  );

  /** 送入模型用于关联判断的其他问题数量上限，避免上下文与成本失控。 */
  private static final int OTHER_ISSUE_LIMIT = 60;

  private final IssueRepository issues;
  private final AiClient aiClient;
  private final ObjectMapper objectMapper;
  private final DataScopeService dataScopeService;

  /** 对全部复发问题做根因归纳。 */
  public Map<String, Object> analyzeAll() {
    return analyzeAll(null);
  }

  /** 对全部复发问题做根因归纳。 */
  public Map<String, Object> analyzeAll(AuthUser user) {
    List<Issue> all = activeIssues(user);
    List<Issue> reopened = all
      .stream()
      .filter(issue -> Boolean.TRUE.equals(issue.getReopened()))
      .toList();

    List<Map<String, Object>> analyses = new ArrayList<>();
    for (Issue issue : reopened) {
      analyses.add(analyzeIssue(issue, all));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generatedAt", LocalDateTime.now().toString());
    result.put("aiAvailable", aiClient.available());
    result.put("reopenedCount", reopened.size());
    result.put("analyzedCount", analyses.size());
    result.put("analyses", analyses);
    if (reopened.isEmpty()) {
      result.put("message", "当前没有标记为复发的问题，无需根因归纳。");
    }
    return result;
  }

  /** 对单个问题做根因归纳。 */
  public Map<String, Object> analyzeOne(Long issueId) {
    return analyzeOne(null, issueId);
  }

  /** 对单个问题做根因归纳。 */
  public Map<String, Object> analyzeOne(AuthUser user, Long issueId) {
    List<Issue> all = activeIssues(user);
    Issue issue = all
      .stream()
      .filter(item -> item.getId() != null && item.getId().equals(issueId))
      .findFirst()
      .orElseThrow(() ->
        new IllegalArgumentException("问题不存在或已删除：" + issueId)
      );
    return analyzeIssue(issue, all);
  }

  // ---------------------------------------------------------------------------
  // 编排：证据 -> 推理 -> 校验
  // ---------------------------------------------------------------------------

  private Map<String, Object> analyzeIssue(Issue issue, List<Issue> all) {
    Map<String, Object> evidence = buildEvidence(issue, all); // ① 证据层
    Set<String> validNos = validIssueNos(all);

    Optional<Map<String, Object>> generated = aiClient.available() // ② 推理层
      ? aiClient.chatJson(systemPrompt(), userPrompt(evidence))
      : Optional.empty();

    if (generated.isEmpty()) {
      return evidenceOnlyFallback(evidence, validNos); // 诚实兜底
    }
    return verify(evidence, generated.get(), validNos); // ③ 校验层
  }

  // ---------------------------------------------------------------------------
  // ① 证据层
  // ---------------------------------------------------------------------------

  private Map<String, Object> buildEvidence(Issue issue, List<Issue> all) {
    Map<String, Object> target = map(
      "issueNo",
      issueNo(issue),
      "title",
      value(issue.getTitle(), "未命名问题"),
      "description",
      textOrUnknown(issue.getDescription()),
      "source",
      textOrUnknown(issue.getSource()),
      "businessScene",
      textOrUnknown(issue.getBusinessScene()),
      "issueType",
      textOrUnknown(issue.getIssueType()),
      "impactScope",
      textOrUnknown(issue.getImpactScope()),
      "customerImpact",
      textOrUnknown(issue.getCustomerImpact()),
      "reproduceSteps",
      textOrUnknown(issue.getReproduceSteps()),
      "priority",
      value(issue.getPriority(), "P2"),
      "status",
      value(issue.getStatus(), "待处理"),
      "department",
      textOrUnknown(issue.getResponsibleDepartment()),
      "owner",
      textOrUnknown(issue.getResponsiblePerson()),
      "rootCause",
      textOrUnknown(issue.getRootCause()),
      "fixSolution",
      textOrUnknown(issue.getFixSolution()),
      "verifyResult",
      textOrUnknown(issue.getVerifyResult()),
      "reopened",
      Boolean.TRUE.equals(issue.getReopened()),
      "reopenedReason",
      textOrUnknown(issue.getReopenedReason())
    );

    List<Map<String, Object>> timeline = issue.getLogs() == null
      ? List.of()
      : issue
        .getLogs()
        .stream()
        .sorted(
          Comparator.comparing(
            IssueLog::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())
          )
        )
        .map(logEntry ->
          map(
            "at",
            String.valueOf(logEntry.getCreatedAt()),
            "actionType",
            value(logEntry.getActionType(), "处理记录"),
            "content",
            textOrUnknown(logEntry.getContent()),
            "operator",
            value(logEntry.getOperator(), "未知")
          )
        )
        .toList();

    List<Map<String, Object>> others = all
      .stream()
      .filter(item -> !sameIssue(item, issue))
      .limit(OTHER_ISSUE_LIMIT)
      .map(item ->
        map(
          "issueNo",
          issueNo(item),
          "title",
          value(item.getTitle(), "未命名问题"),
          "businessScene",
          textOrUnknown(item.getBusinessScene()),
          "issueType",
          textOrUnknown(item.getIssueType()),
          "rootCause",
          textOrUnknown(item.getRootCause())
        )
      )
      .toList();

    return map("targetIssue", target, "timeline", timeline, "otherIssues", others);
  }

  // ---------------------------------------------------------------------------
  // ③ 校验层
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Map<String, Object> verify(
    Map<String, Object> evidence,
    Map<String, Object> ai,
    Set<String> validNos
  ) {
    Map<String, Object> target = (Map<String, Object>) evidence.get("targetIssue");
    String selfNo = String.valueOf(target.get("issueNo"));

    List<String> droppedCorrelations = new ArrayList<>();
    Set<String> fabricatedRefs = new LinkedHashSet<>();

    // 根因假设：clamp 置信度、扫描证据中夹带的伪造编号
    List<Map<String, Object>> hypotheses = new ArrayList<>();
    double maxConfidence = 0.0;
    if (ai.get("rootCauseHypotheses") instanceof List<?> rawList) {
      for (Object raw : rawList) {
        if (!(raw instanceof Map<?, ?> hypothesisMap)) continue;
        String hypothesis = stringValue(hypothesisMap.get("hypothesis"));
        if (isBlank(hypothesis)) continue;
        double confidence = clamp01(toDouble(hypothesisMap.get("confidence")));
        maxConfidence = Math.max(maxConfidence, confidence);
        List<String> evidenceRefs = stringList(hypothesisMap.get("evidence"), 6);
        List<String> fabricatedHere = fabricatedNos(evidenceRefs, validNos);
        fabricatedRefs.addAll(fabricatedHere);
        hypotheses.add(
          map(
            "hypothesis",
            hypothesis,
            "confidence",
            confidence,
            "evidence",
            evidenceRefs.isEmpty() ? List.of("数据不足") : evidenceRefs,
            "grounded",
            fabricatedHere.isEmpty() && !evidenceRefs.isEmpty()
          )
        );
      }
    }

    // 关联问题：结构化字段严格对账，引用不存在的 issueNo 或指向自身一律剔除
    List<Map<String, Object>> correlated = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    if (ai.get("correlatedIssues") instanceof List<?> rawList) {
      for (Object raw : rawList) {
        if (!(raw instanceof Map<?, ?> correlatedMap)) continue;
        String issueNo = stringValue(correlatedMap.get("issueNo"));
        if (isBlank(issueNo)) continue;
        if (!validNos.contains(issueNo) || issueNo.equals(selfNo)) {
          droppedCorrelations.add(issueNo);
          continue;
        }
        if (!used.add(issueNo)) continue;
        String relation = stringValue(correlatedMap.get("relation"));
        correlated.add(
          map(
            "issueNo",
            issueNo,
            "relation",
            isBlank(relation) ? "（未说明关联理由）" : relation
          )
        );
      }
    }

    boolean needHumanReview =
      Boolean.TRUE.equals(ai.get("needHumanReview")) ||
      !fabricatedRefs.isEmpty() ||
      hypotheses.isEmpty() ||
      maxConfidence < CONFIDENCE_REVIEW_THRESHOLD;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issueNo", selfNo);
    result.put("title", target.get("title"));
    result.put(
      "recurrenceSummary",
      orFallback(stringValue(ai.get("recurrenceSummary")), "数据不足，无法判断复发根因")
    );
    result.put("rootCauseHypotheses", hypotheses);
    result.put(
      "whyPreviousFixFailed",
      orFallback(stringValue(ai.get("whyPreviousFixFailed")), "数据不足")
    );
    result.put("correlatedIssues", correlated);
    result.put("systemicFix", stringList(ai.get("systemicFix"), 6));
    result.put("verifyPlan", stringList(ai.get("verifyPlan"), 6));
    result.put("needHumanReview", needHumanReview);
    result.put(
      "groundingReport",
      map(
        "validIssueNoCount",
        validNos.size(),
        "droppedCorrelations",
        droppedCorrelations,
        "fabricatedReferences",
        new ArrayList<>(fabricatedRefs),
        "maxConfidence",
        maxConfidence
      )
    );
    result.put("analysisMode", "ai");
    result.put("generatedBy", aiClient.provider());
    result.put("model", aiClient.model());
    return result;
  }

  /** 诚实兜底：不伪造任何分析，只回原始证据并标注 AI 未生效。 */
  @SuppressWarnings("unchecked")
  private Map<String, Object> evidenceOnlyFallback(
    Map<String, Object> evidence,
    Set<String> validNos
  ) {
    Map<String, Object> target = (Map<String, Object>) evidence.get("targetIssue");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issueNo", target.get("issueNo"));
    result.put("title", target.get("title"));
    result.put(
      "recurrenceSummary",
      aiClient.available()
        ? "AI 分析暂不可用（模型未返回有效结果），以下为原始证据，请人工归纳。"
        : "未配置可用的 AI 模型，以下为原始证据，请人工归纳。"
    );
    result.put("rootCauseHypotheses", List.of());
    result.put("whyPreviousFixFailed", "数据不足");
    result.put("correlatedIssues", List.of());
    result.put("systemicFix", List.of());
    result.put("verifyPlan", List.of());
    result.put("needHumanReview", true);
    result.put("evidence", evidence);
    result.put("groundingReport", map("validIssueNoCount", validNos.size()));
    result.put("analysisMode", "evidence-only");
    result.put("generatedBy", "local-rules");
    result.put("model", "local-rules");
    return result;
  }

  // ---------------------------------------------------------------------------
  // Prompt
  // ---------------------------------------------------------------------------

  private String systemPrompt() {
    return """
      你是企业问题治理的根因分析专家。任务：针对一个反复复发的问题，基于给定的结构化证据
      （问题字段、处理时间线、其他在库问题），归纳根因假设并给出根治建议。
      硬性要求：
      1. 只能引用证据中真实出现的 issueNo 与记录，严禁编造问题、数量、责任人、时间、超期天数或复发次数。
      2. 每条根因假设必须带 confidence（0 到 1 的小数）和 evidence（引用证据原文或 issueNo）。
      3. 必须重点解释：为什么之前的修复没能挡住这次复发。
      4. 任何证据不足以支撑的结论，必须显式写“数据不足”。
      5. 只输出严格 JSON，不要 Markdown，不要代码块，不要多余文字。
      """;
  }

  private String userPrompt(Map<String, Object> evidence) {
    return (
      """
      请对以下复发问题做根因归纳，返回 JSON：
      {
        "issueNo": "必须来自证据中的 targetIssue.issueNo",
        "recurrenceSummary": "一句话说明它为什么会反复",
        "rootCauseHypotheses": [
          {"hypothesis": "...", "confidence": 0.0, "evidence": ["引用证据原文或 issueNo"]}
        ],
        "whyPreviousFixFailed": "解释为什么之前的修复仍然复发",
        "correlatedIssues": [
          {"issueNo": "必须来自 otherIssues 中真实存在的编号", "relation": "为什么可能同源/可关联"}
        ],
        "systemicFix": ["根治动作"],
        "verifyPlan": ["如何验证根治是否真正生效"],
        "needHumanReview": true
      }

      证据：
      """ +
      toJson(evidence)
    );
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<Issue> activeIssues() {
    return activeIssues(null);
  }

  private List<Issue> activeIssues(AuthUser user) {
    return issues
      .findAll()
      .stream()
      .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
      .filter(issue -> user == null || dataScopeService.canSee(user, issue))
      .toList();
  }

  private Set<String> validIssueNos(List<Issue> all) {
    Set<String> nos = new LinkedHashSet<>();
    for (Issue issue : all) {
      nos.add(issueNo(issue));
    }
    return nos;
  }

  private String issueNo(Issue issue) {
    return value(issue.getIssueNo(), "PBI-" + issue.getId());
  }

  private boolean sameIssue(Issue a, Issue b) {
    return a.getId() != null && a.getId().equals(b.getId());
  }

  /** 在自由文本证据中找出形似 issueNo 但不在有效集合内的伪造引用。 */
  private List<String> fabricatedNos(List<String> evidenceRefs, Set<String> validNos) {
    List<String> fabricated = new ArrayList<>();
    for (String ref : evidenceRefs) {
      if (ref == null) continue;
      Matcher matcher = ISSUE_NO_TOKEN.matcher(ref);
      while (matcher.find()) {
        String token = matcher.group();
        if (!validNos.contains(token) && !fabricated.contains(token)) {
          fabricated.add(token);
        }
      }
    }
    return fabricated;
  }

  private double toDouble(Object value) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (Exception ignored) {
      return 0.0;
    }
  }

  private double clamp01(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }

  private String orFallback(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private String textOrUnknown(String value) {
    return isBlank(value) ? "未填写" : value.trim();
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return "{}";
    }
  }

  private List<String> stringList(Object value, int limit) {
    if (value instanceof Collection<?> collection) {
      return collection
        .stream()
        .map(this::stringValue)
        .filter(text -> !isBlank(text))
        .distinct()
        .limit(limit)
        .toList();
    }
    String text = stringValue(value);
    if (isBlank(text)) return List.of();
    return List.of(text);
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String value(String value, String fallback) {
    return isBlank(value) ? fallback : value.trim();
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private Map<String, Object> map(Object... pairs) {
    Map<String, Object> value = new LinkedHashMap<>();
    for (int index = 0; index + 1 < pairs.length; index += 2) {
      if (pairs[index] == null) continue;
      Object entryValue = pairs[index + 1];
      if (entryValue == null) continue;
      value.put(String.valueOf(pairs[index]), entryValue);
    }
    return value;
  }
}
