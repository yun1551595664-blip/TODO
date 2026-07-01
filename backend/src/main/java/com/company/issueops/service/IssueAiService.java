package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IssueAiService {

  private final IssueRepository issues;
  private final AiClient aiClient;
  private final ObjectMapper objectMapper;

  public Map<String, Object> analyze(Long issueId, String type) {
    Issue issue = issues
      .findById(issueId)
      .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
      .orElseThrow(() -> new NoSuchElementException("问题不存在：" + issueId));
    List<Issue> activeIssues = issues
      .findAll()
      .stream()
      .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
      .toList();

    Map<String, Object> context = buildContext(issue, activeIssues);
    String normalizedType = normalizeType(type);
    Optional<Map<String, Object>> generated = aiClient.available()
      ? aiClient.chatJson(systemPrompt(), userPrompt(normalizedType, context))
      : Optional.empty();

    Map<String, Object> result = generated
      .map(value -> normalizeResult(normalizedType, value, context))
      .orElseGet(() -> localFallback(normalizedType, context));
    result.put("type", normalizedType);
    result.put("issueId", issue.getId());
    result.put("issueNo", value(issue.getIssueNo(), "PBI-" + issue.getId()));
    result.put("generatedAt", LocalDateTime.now().toString());
    result.put("generatedBy", generated.isPresent() ? aiClient.provider() : "local-rules");
    result.put("model", generated.isPresent() ? aiClient.model() : "local-rules");
    if (generated.isEmpty()) {
      result.put("aiError", "AI 分析暂不可用，当前展示本地规则草稿");
    }
    return result;
  }

  private Map<String, Object> buildContext(Issue issue, List<Issue> all) {
    Map<String, Object> target = map(
      "id",
      issue.getId(),
      "issueNo",
      value(issue.getIssueNo(), "PBI-" + issue.getId()),
      "title",
      value(issue.getTitle(), "未命名问题"),
      "description",
      text(issue.getDescription()),
      "source",
      text(issue.getSource()),
      "businessScene",
      text(issue.getBusinessScene()),
      "issueType",
      text(issue.getIssueType()),
      "impactScope",
      text(issue.getImpactScope()),
      "customerImpact",
      text(issue.getCustomerImpact()),
      "reproduceSteps",
      text(issue.getReproduceSteps()),
      "priority",
      value(issue.getPriority(), "P2"),
      "status",
      value(issue.getStatus(), "待处理"),
      "department",
      text(issue.getResponsibleDepartment()),
      "owner",
      text(issue.getResponsiblePerson()),
      "rootCause",
      text(issue.getRootCause()),
      "fixSolution",
      text(issue.getFixSolution()),
      "verifyResult",
      text(issue.getVerifyResult()),
      "reopened",
      Boolean.TRUE.equals(issue.getReopened()),
      "reopenedReason",
      text(issue.getReopenedReason())
    );

    List<Map<String, Object>> logs = issue.getLogs() == null
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
        .map(log ->
          map(
            "actionType",
            value(log.getActionType(), "处理记录"),
            "content",
            text(log.getContent()),
            "operator",
            value(log.getOperator(), "未知"),
            "createdAt",
            String.valueOf(log.getCreatedAt())
          )
        )
        .toList();

    List<Map<String, Object>> related = all
      .stream()
      .filter(item -> item.getId() != null && !item.getId().equals(issue.getId()))
      .filter(item -> looksRelated(issue, item))
      .limit(8)
      .map(item ->
        map(
          "id",
          item.getId(),
          "issueNo",
          value(item.getIssueNo(), "PBI-" + item.getId()),
          "title",
          value(item.getTitle(), "未命名问题"),
          "businessScene",
          text(item.getBusinessScene()),
          "issueType",
          text(item.getIssueType()),
          "status",
          value(item.getStatus(), "待处理"),
          "priority",
          value(item.getPriority(), "P2"),
          "department",
          text(item.getResponsibleDepartment()),
          "reopened",
          Boolean.TRUE.equals(item.getReopened())
        )
      )
      .toList();

    return map("targetIssue", target, "logs", logs, "relatedCandidates", related);
  }

  private boolean looksRelated(Issue source, Issue candidate) {
    if (sameText(source.getBusinessScene(), candidate.getBusinessScene())) return true;
    if (sameText(source.getIssueType(), candidate.getIssueType())) return true;
    String title = value(source.getTitle(), "");
    String other = value(candidate.getTitle(), "");
    return !title.isBlank() && !other.isBlank() && (
      title.contains(other) ||
      other.contains(title) ||
      shareMeaningfulToken(title, other)
    );
  }

  private boolean shareMeaningfulToken(String left, String right) {
    for (String token : left.split("[\\s,，、;；:：/\\\\-]+")) {
      String normalized = token.trim();
      if (normalized.length() >= 3 && right.contains(normalized)) return true;
    }
    return false;
  }

  private boolean sameText(String left, String right) {
    return left != null && right != null && !left.isBlank() && left.trim().equals(right.trim());
  }

  private Map<String, Object> normalizeResult(
    String type,
    Map<String, Object> generated,
    Map<String, Object> context
  ) {
    Map<String, Object> fallback = localFallback(type, context);
    String title = stringValue(generated.get("title"));
    String summary = stringValue(generated.get("summary"));
    if (!title.isBlank()) fallback.put("title", title);
    if (!summary.isBlank()) fallback.put("summary", summary);

    List<String> evidence = stringList(generated.get("evidence"), 6);
    if (!evidence.isEmpty()) fallback.put("evidence", evidence);
    List<String> actions = stringList(generated.get("suggestedActions"), 6);
    if (!actions.isEmpty()) fallback.put("suggestedActions", actions);
    List<String> draft = stringList(generated.get("draft"), 8);
    if (!draft.isEmpty()) fallback.put("draft", draft);
    List<Map<String, Object>> related = normalizeRelated(generated.get("relatedIssues"), context);
    fallback.put("relatedIssues", related);
    return fallback;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> normalizeRelated(
    Object rawRelated,
    Map<String, Object> context
  ) {
    List<Map<String, Object>> candidates = (List<Map<String, Object>>) context.get(
      "relatedCandidates"
    );
    if (!(rawRelated instanceof List<?> list) || candidates.isEmpty()) return List.of();

    Map<String, Map<String, Object>> index = new LinkedHashMap<>();
    for (Map<String, Object> candidate : candidates) {
      index.put(String.valueOf(candidate.get("id")), candidate);
      index.put(String.valueOf(candidate.get("issueNo")), candidate);
    }
    List<Map<String, Object>> related = new ArrayList<>();
    for (Object value : list) {
      Map<String, Object> item = index.get(String.valueOf(value));
      if (item != null) related.add(item);
      if (related.size() >= 4) break;
    }
    return related;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> localFallback(String type, Map<String, Object> context) {
    Map<String, Object> issue = (Map<String, Object>) context.get("targetIssue");
    String title = String.valueOf(issue.get("title"));
    return switch (type) {
      case "root-cause" -> map(
        "title",
        "归因草稿",
        "summary",
        "需要结合日志、复现步骤和最近状态变更继续确认根因。当前草稿只基于已录入字段生成。",
        "evidence",
        List.of("问题：" + title, "状态：" + issue.get("status"), "优先级：" + issue.get("priority")),
        "suggestedActions",
        List.of("补充稳定复现步骤", "核对上下游调用日志", "将确认后的根因写回详情页"),
        "draft",
        List.of(
          "初步判断：" + title + " 可能与当前业务场景中的边界条件、状态同步或配置差异有关。",
          "待验证证据：接口日志、用户操作路径、最近发布/配置变更。"
        ),
        "relatedIssues",
        List.of()
      );
      case "duplicate" -> map(
        "title",
        "重复风险判断",
        "summary",
        "当前只发现候选相似问题，需要人工确认是否同源或重复。",
        "evidence",
        List.of("业务场景：" + issue.get("businessScene"), "问题类型：" + issue.get("issueType")),
        "suggestedActions",
        List.of("比对标题、业务场景、根因和复现路径", "确认重复后在处理记录中建立关联"),
        "draft",
        List.of("可将相似问题合并为一个治理主题，并保留原问题编号作为证据。"),
        "relatedIssues",
        List.of()
      );
      default -> map(
        "title",
        "处理草稿与优化建议",
        "summary",
        "建议先补齐责任、时间点、验证标准，再推进修复和复盘。",
        "evidence",
        List.of("影响范围：" + issue.get("impactScope"), "客户影响：" + issue.get("customerImpact")),
        "suggestedActions",
        List.of("明确下一步负责人和时间点", "补充验收标准", "修复后观察 7 天并沉淀复盘结论"),
        "draft",
        List.of("处理记录草稿：已基于当前问题信息整理下一步计划，请补充实际定位证据后提交。"),
        "relatedIssues",
        List.of()
      );
    };
  }

  private String normalizeType(String type) {
    String normalized = value(type, "").trim();
    return switch (normalized) {
      case "root-cause", "duplicate", "suggestion" -> normalized;
      default -> "suggestion";
    };
  }

  private String systemPrompt() {
    return """
      你是“产品与业务问题进度管理看板”的企业级问题治理助手。
      你只能基于输入的问题详情、处理记录和候选相似问题分析，不能编造不存在的问题、责任人、部门、时间、数量或外部系统信息。
      当前 AI 不能直接修改系统数据，只能生成草稿、判断依据和建议动作；需要用户确认后在本系统内提交。
      如果数据不足，必须直接说明“当前数据不足，无法判断”。
      输出严格 JSON，不要 Markdown，不要代码块。
      """;
  }

  private String userPrompt(String type, Map<String, Object> context) {
    return (
      """
      请根据 type 生成真实 AI 分析结果：
      - root-cause：生成归因草稿，给出依据和待验证证据。
      - suggestion：生成处理记录/优化建议草稿，强调下一步动作。
      - duplicate：判断是否存在重复或同源问题，只能引用 relatedCandidates 中真实存在的问题。

      返回 JSON：
      {
        "title": "结果标题",
        "summary": "一句话结论",
        "evidence": ["依据，必须来自输入"],
        "suggestedActions": ["可执行动作"],
        "draft": ["可直接复制到处理记录或复盘中的草稿"],
        "relatedIssues": ["相关问题 id 或 issueNo；没有就返回空数组"]
      }

      type：
      """ +
      type +
      """

      输入上下文：
      """ +
      toJson(context)
    );
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return "{}";
    }
  }

  private List<String> stringList(Object value, int limit) {
    if (value instanceof Iterable<?> iterable) {
      List<String> result = new ArrayList<>();
      for (Object item : iterable) {
        String text = stringValue(item);
        if (!text.isBlank() && !result.contains(text)) result.add(text);
        if (result.size() >= limit) break;
      }
      return result;
    }
    String text = stringValue(value);
    return text.isBlank() ? List.of() : List.of(text);
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String text(String value) {
    return value(value, "未填写");
  }

  private String value(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private Map<String, Object> map(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      map.put(String.valueOf(values[i]), values[i + 1]);
    }
    return map;
  }
}
