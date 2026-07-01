package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.repository.IssueRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetrospectiveService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd");
  private static final Set<String> HIGH_PRIORITIES = Set.of("P0", "P1");

  private final IssueRepository issues;
  private final AiClient aiClient;
  private final ObjectMapper objectMapper;

  public Map<String, Object> overview() {
    List<Issue> issueList = loadIssues();
    LocalDateTime now = LocalDateTime.now();
    List<Map<String, Object>> queue = issueList
      .stream()
      .map(issue -> reviewQueueItem(issue, now))
      .sorted(
        Comparator
          .<Map<String, Object>>comparingInt(item -> intValue(item.get("score")))
          .reversed()
      )
      .limit(21)
      .toList();

    List<Map<String, Object>> clusters = causeClusters(issueList, now);
    List<Map<String, Object>> actions = preventionActions(queue);
    Map<String, Object> local = map(
      "updatedAt",
      LocalDateTime.now().toString(),
      "period",
      "近 30 天",
      "pipeline",
      pipeline(issueList, queue, actions),
      "reviewQueue",
      queue,
      "causeClusters",
      clusters,
      "actionClosure",
      actionClosure(actions),
      "aiSuggestion",
      aiSuggestionPlaceholder(),
      "modelInfo",
      map("provider", aiClient.provider(), "model", aiClient.model()),
      "aiAvailable",
      aiClient.available()
    );
    return local;
  }

  public Map<String, Object> aiSuggestion() {
    List<Issue> issueList = loadIssues();
    LocalDateTime now = LocalDateTime.now();
    List<Map<String, Object>> queue = issueList
      .stream()
      .map(issue -> reviewQueueItem(issue, now))
      .sorted(
        Comparator
          .<Map<String, Object>>comparingInt(item -> intValue(item.get("score")))
          .reversed()
      )
      .limit(21)
      .toList();
    List<Map<String, Object>> clusters = causeClusters(issueList, now);
    List<Map<String, Object>> actions = preventionActions(queue);
    return generateAiSuggestion(queue, clusters, actions);
  }

  public Map<String, Object> draft(Map<String, Object> body) {
    if (!aiClient.available()) {
      return map(
        "available",
        false,
        "generatedBy",
        "none",
        "model",
        aiClient.model(),
        "error",
        "AI 未配置或暂不可用，无法生成复盘草稿。"
      );
    }

    Long issueId = longValue(body.get("issueId"));
    List<Issue> issueList = loadIssues();
    Issue selected = issueList
      .stream()
      .filter(issue -> Objects.equals(issue.getId(), issueId))
      .findFirst()
      .orElseGet(() -> issueList.isEmpty() ? null : issueList.get(0));
    if (selected == null) {
      return map(
        "available",
        false,
        "generatedBy",
        "none",
        "model",
        aiClient.model(),
        "error",
        "当前没有可用于复盘的问题数据。"
      );
    }

    List<Map<String, Object>> related = issueList
      .stream()
      .filter(issue -> !Objects.equals(issue.getId(), selected.getId()))
      .filter(issue -> isRelated(selected, issue))
      .limit(6)
      .map(this::issuePromptMap)
      .toList();

    Optional<Map<String, Object>> generated = aiClient.chatJson(
      retrospectiveSystemPrompt(),
      draftUserPrompt(selected, related)
    );
    if (generated.isEmpty()) {
      return map(
        "available",
        false,
        "generatedBy",
        aiClient.provider(),
        "model",
        aiClient.model(),
        "error",
        "AI 返回内容无法解析为结构化复盘草稿，请稍后重试。"
      );
    }

    Map<String, Object> draft = normalizeDraft(generated.get(), selected);
    draft.put("available", true);
    draft.put("generatedBy", aiClient.provider());
    draft.put("model", aiClient.model());
    draft.put("generatedAt", LocalDateTime.now().toString());
    return draft;
  }

  private List<Issue> loadIssues() {
    return issues
      .findAll()
      .stream()
      .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
      .sorted(
        Comparator
          .comparing(
            (Issue issue) -> Optional.ofNullable(issue.getUpdatedAt()).orElse(
              issue.getCreatedAt()
            ),
            Comparator.nullsLast(Comparator.naturalOrder())
          )
          .reversed()
      )
      .toList();
  }

  private Map<String, Object> pipeline(
    List<Issue> issueList,
    List<Map<String, Object>> queue,
    List<Map<String, Object>> actions
  ) {
    long closed = issueList.stream().filter(this::isCompleted).count();
    long rootArchived = issueList.stream().filter(issue -> !isBlank(issue.getRootCause())).count();
    long verified = issueList.stream().filter(issue -> !isBlank(issue.getVerifyResult())).count();
    long actionCount = actions.size();
    long reusable = issueList
      .stream()
      .filter(issue -> !isBlank(issue.getFixSolution()) && !isBlank(issue.getRootCause()))
      .count();

    return map(
      "steps",
      List.of(
        pipelineStep("问题关闭", "已关闭的待复盘问题", closed),
        pipelineStep("根因归档", "归因完成待归档", rootArchived),
        pipelineStep("方案验证", "验证中的问题", verified),
        pipelineStep("预防动作", "待落地的预防动作", actionCount),
        pipelineStep("经验复用", "沉淀到知识库", reusable)
      ),
      "queueCount",
      queue.size()
    );
  }

  private Map<String, Object> pipelineStep(String label, String description, long value) {
    return map("label", label, "description", description, "value", value);
  }

  private Map<String, Object> reviewQueueItem(Issue issue, LocalDateTime now) {
    int overdueDays = overdueDays(issue, now);
    String reviewStatus = reviewStatus(issue);
    int score = score(issue, overdueDays, reviewStatus);
    return map(
      "id",
      issue.getId(),
      "issueNo",
      value(issue.getIssueNo(), "ISSUE-" + issue.getId()),
      "title",
      value(issue.getTitle(), "未命名问题"),
      "priority",
      value(issue.getPriority(), "P2"),
      "status",
      value(issue.getStatus(), "待处理"),
      "retrospectiveStatus",
      reviewStatus,
      "reviewReason",
      reviewReason(issue, overdueDays, reviewStatus),
      "department",
      value(issue.getResponsibleDepartment(), "未分配"),
      "owner",
      value(issue.getResponsiblePerson(), "未分配"),
      "deadline",
      deadline(issue),
      "overdueDays",
      overdueDays,
      "rootCauseTag",
      causeCategory(issue),
      "impact",
      impact(issue),
      "score",
      score
    );
  }

  private int score(Issue issue, int overdueDays, String reviewStatus) {
    int score = switch (value(issue.getPriority(), "P2")) {
      case "P0" -> 80;
      case "P1" -> 64;
      case "P2" -> 42;
      default -> 24;
    };
    if (Boolean.TRUE.equals(issue.getReopened())) score += 28;
    score += Math.min(28, overdueDays * 4);
    if ("待归因".equals(reviewStatus)) score += 16;
    if ("待验证".equals(reviewStatus)) score += 10;
    if (isCompleted(issue)) score += 6;
    return score;
  }

  private String reviewStatus(Issue issue) {
    if (isBlank(issue.getRootCause())) return "待归因";
    if (isBlank(issue.getVerifyResult())) return "待验证";
    if (isBlank(issue.getFixSolution())) return "待沉淀";
    return "已沉淀";
  }

  private String reviewReason(Issue issue, int overdueDays, String reviewStatus) {
    List<String> reasons = new ArrayList<>();
    if (HIGH_PRIORITIES.contains(issue.getPriority())) reasons.add("高优先级问题");
    if (Boolean.TRUE.equals(issue.getReopened())) reasons.add("存在复发记录");
    if (overdueDays > 0) reasons.add("已超期 " + overdueDays + " 天");
    if ("待归因".equals(reviewStatus)) reasons.add("缺少根因结论");
    if ("待验证".equals(reviewStatus)) reasons.add("缺少验证结果");
    if (reasons.isEmpty()) reasons.add("已关闭问题需要经验沉淀");
    return String.join("，", reasons);
  }

  private List<Map<String, Object>> causeClusters(
    List<Issue> issueList,
    LocalDateTime now
  ) {
    LocalDateTime recentStart = now.minusDays(30);
    LocalDateTime previousStart = now.minusDays(60);
    Map<String, List<Issue>> grouped = issueList
      .stream()
      .collect(Collectors.groupingBy(this::causeCategory, LinkedHashMap::new, Collectors.toList()));

    return grouped
      .entrySet()
      .stream()
      .map(entry -> {
        long recent = entry
          .getValue()
          .stream()
          .filter(issue -> after(issue.getCreatedAt(), recentStart))
          .count();
        long previous = entry
          .getValue()
          .stream()
          .filter(issue ->
            after(issue.getCreatedAt(), previousStart) &&
            !after(issue.getCreatedAt(), recentStart)
          )
          .count();
        int change = previous == 0
          ? (recent > 0 ? 100 : 0)
          : (int) Math.round(((recent - previous) * 100.0) / previous);
        return map(
          "name",
          entry.getKey(),
          "count",
          entry.getValue().size(),
          "share",
          issueList.isEmpty()
            ? 0
            : Math.round((entry.getValue().size() * 100.0) / issueList.size()),
          "changePercent",
          change,
          "issueNos",
          entry.getValue().stream().limit(5).map(Issue::getIssueNo).toList()
        );
      })
      .sorted(
        Comparator
          .<Map<String, Object>>comparingInt(item -> intValue(item.get("count")))
          .reversed()
      )
      .limit(5)
      .toList();
  }

  private List<Map<String, Object>> preventionActions(
    List<Map<String, Object>> queue
  ) {
    return queue
      .stream()
      .limit(6)
      .map(item -> {
        String status = stringValue(item.get("retrospectiveStatus"));
        int progress = switch (status) {
          case "已沉淀" -> 100;
          case "待沉淀" -> 68;
          case "待验证" -> 42;
          default -> 18;
        };
        return map(
          "title",
          actionTitle(item),
          "owner",
          value(stringValue(item.get("owner")), "未分配"),
          "department",
          value(stringValue(item.get("department")), "未分配"),
          "deadline",
          item.get("deadline"),
          "progress",
          progress,
          "status",
          progress >= 100 ? "已完成" : progress >= 40 ? "进行中" : "待落地",
          "sourceIssueId",
          item.get("id"),
          "sourceIssueNo",
          item.get("issueNo")
        );
      })
      .toList();
  }

  private Map<String, Object> actionClosure(List<Map<String, Object>> actions) {
    long completed = actions
      .stream()
      .filter(item -> "已完成".equals(stringValue(item.get("status"))))
      .count();
    long inProgress = actions
      .stream()
      .filter(item -> "进行中".equals(stringValue(item.get("status"))))
      .count();
    long pending = actions.size() - completed - inProgress;
    int rate = actions.isEmpty()
      ? 0
      : (int) Math.round((completed * 100.0) / actions.size());
    return map(
      "pending",
      pending,
      "inProgress",
      inProgress,
      "completed",
      completed,
      "completionRate",
      rate,
      "actions",
      actions
    );
  }

  private Map<String, Object> aiSuggestionPlaceholder() {
    return map(
      "available",
      aiClient.available(),
      "applied",
      false,
      "generatedBy",
      "pending",
      "model",
      aiClient.model(),
      "error",
      aiClient.available()
        ? "AI 复盘建议正在单独加载。"
        : "AI 未配置或暂不可用，复盘建议不会使用本地文案冒充。"
    );
  }

  private Map<String, Object> generateAiSuggestion(
    List<Map<String, Object>> queue,
    List<Map<String, Object>> clusters,
    List<Map<String, Object>> actions
  ) {
    if (!aiClient.available()) {
      return map(
        "available",
        false,
        "applied",
        false,
        "generatedBy",
        "none",
        "model",
        aiClient.model(),
        "error",
        "AI 未配置或暂不可用，复盘建议不会使用本地文案冒充。"
      );
    }
    Optional<Map<String, Object>> generated = aiClient.chatJson(
      retrospectiveSystemPrompt(),
      overviewUserPrompt(queue, clusters, actions)
    );
    if (generated.isEmpty()) {
      return map(
        "available",
        true,
        "applied",
        false,
        "generatedBy",
        aiClient.provider(),
        "model",
        aiClient.model(),
        "error",
        "AI 返回内容无法解析，暂不展示复盘建议。"
      );
    }
    Map<String, Object> normalized = normalizeSuggestion(generated.get(), queue);
    normalized.put("available", true);
    normalized.put("applied", true);
    normalized.put("generatedBy", aiClient.provider());
    normalized.put("model", aiClient.model());
    normalized.put("generatedAt", LocalDateTime.now().toString());
    return normalized;
  }

  private Map<String, Object> normalizeSuggestion(
    Map<String, Object> generated,
    List<Map<String, Object>> queue
  ) {
    Set<String> knownIssueNos = queue
      .stream()
      .map(item -> stringValue(item.get("issueNo")))
      .filter(value -> !value.isBlank())
      .collect(Collectors.toSet());
    List<String> priorityIssueNos = stringList(generated.get("priorityIssueNos"))
      .stream()
      .filter(knownIssueNos::contains)
      .limit(3)
      .toList();

    return map(
      "summary",
      value(stringValue(generated.get("summary")), "AI 已返回分析，但摘要为空。"),
      "priorityIssueNos",
      priorityIssueNos,
      "evidence",
      stringList(generated.get("evidence")).stream().limit(5).toList(),
      "nextActions",
      stringList(generated.get("nextActions")).stream().limit(5).toList()
    );
  }

  private Map<String, Object> normalizeDraft(Map<String, Object> generated, Issue issue) {
    return map(
      "issueId",
      issue.getId(),
      "issueNo",
      issue.getIssueNo(),
      "title",
      issue.getTitle(),
      "rootCauseDraft",
      value(stringValue(generated.get("rootCauseDraft")), ""),
      "fixReview",
      value(stringValue(generated.get("fixReview")), ""),
      "verificationConclusion",
      value(stringValue(generated.get("verificationConclusion")), ""),
      "preventionActions",
      stringList(generated.get("preventionActions")).stream().limit(8).toList(),
      "reusePlaybook",
      stringList(generated.get("reusePlaybook")).stream().limit(8).toList(),
      "evidence",
      stringList(generated.get("evidence")).stream().limit(8).toList()
    );
  }

  private String overviewUserPrompt(
    List<Map<String, Object>> queue,
    List<Map<String, Object>> clusters,
    List<Map<String, Object>> actions
  ) {
    return """
      请基于下面的复盘队列、根因聚类和预防动作生成“AI 复盘建议”。
      只能引用输入中存在的问题编号、问题标题、负责人、部门和数量，不能编造。
      如果数据不足，请明确说明数据不足。
      输出 JSON：
      {
        "summary": "建议优先复盘的综合判断",
        "priorityIssueNos": ["必须来自输入 issueNo，最多 3 个"],
        "evidence": ["来自输入数据的关键证据，最多 5 条"],
        "nextActions": ["可执行下一步，最多 5 条"]
      }

      输入数据：
      %s
      """.formatted(
        json(
          map(
            "queue",
            queue.stream().limit(8).toList(),
            "clusters",
            clusters,
            "actions",
            actions.stream().limit(6).toList()
          )
        )
      );
  }

  private String draftUserPrompt(Issue issue, List<Map<String, Object>> related) {
    return """
      请为指定问题生成可人工确认的复盘草稿。
      必须基于输入字段，不允许编造责任人、部门、时间、数量和不存在的问题。
      如果某个字段证据不足，请在对应段落写“当前证据不足，需要补充”。
      输出 JSON：
      {
        "rootCauseDraft": "根因草稿",
        "fixReview": "修复方案复盘",
        "verificationConclusion": "验证结论草稿",
        "preventionActions": ["预防动作"],
        "reusePlaybook": ["可复用处理步骤"],
        "evidence": ["引用的输入证据"]
      }

      当前问题：
      %s

      相似/关联问题：
      %s
      """.formatted(json(issuePromptMap(issue)), json(related));
  }

  private String retrospectiveSystemPrompt() {
    return """
      你是企业级产品与业务问题治理的复盘分析助手。
      你的任务是基于系统提供的问题数据，识别哪些问题需要复盘、归纳根因、提炼预防动作和可复用经验。
      你不是闲聊助手，不能输出空泛建议。
      严格限制：
      1. 只能使用输入数据中的问题、部门、负责人、状态、时间和数量。
      2. 不能编造不存在的问题编号、责任人、客户影响或复发次数。
      3. 数据不足时必须说明“当前证据不足，需要补充”。
      4. 输出必须是合法 JSON，不要 Markdown，不要代码块。
      """;
  }

  private Map<String, Object> issuePromptMap(Issue issue) {
    return map(
      "id",
      issue.getId(),
      "issueNo",
      issue.getIssueNo(),
      "title",
      issue.getTitle(),
      "priority",
      issue.getPriority(),
      "status",
      issue.getStatus(),
      "department",
      issue.getResponsibleDepartment(),
      "owner",
      issue.getResponsiblePerson(),
      "businessScene",
      issue.getBusinessScene(),
      "issueType",
      issue.getIssueType(),
      "impactScope",
      issue.getImpactScope(),
      "customerImpact",
      issue.getCustomerImpact(),
      "rootCause",
      issue.getRootCause(),
      "fixSolution",
      issue.getFixSolution(),
      "verifyResult",
      issue.getVerifyResult(),
      "reopened",
      issue.getReopened(),
      "reopenedReason",
      issue.getReopenedReason(),
      "expectedFinishTime",
      time(issue.getExpectedFinishTime()),
      "actualFinishTime",
      time(issue.getActualFinishTime()),
      "createdAt",
      time(issue.getCreatedAt()),
      "updatedAt",
      time(issue.getUpdatedAt())
    );
  }

  private String causeCategory(Issue issue) {
    String text = String.join(
      " ",
      value(issue.getRootCause(), ""),
      value(issue.getIssueType(), ""),
      value(issue.getTitle(), ""),
      value(issue.getDescription(), "")
    );
    if (containsAny(text, "配置", "规则", "参数", "策略")) return "配置缺陷";
    if (containsAny(text, "流程", "链路", "状态", "审批", "同步")) return "流程断点";
    if (containsAny(text, "监控", "告警", "日志", "观测")) return "监控缺失";
    if (containsAny(text, "数据", "表", "字段", "一致", "导入")) return "数据异常";
    if (containsAny(text, "权限", "账号", "角色", "登录")) return "权限策略";
    return value(issue.getIssueType(), "其他问题");
  }

  private String impact(Issue issue) {
    if (!isBlank(issue.getCustomerImpact())) return issue.getCustomerImpact().trim();
    if (!isBlank(issue.getImpactScope())) return issue.getImpactScope().trim();
    if (!isBlank(issue.getBusinessScene())) return issue.getBusinessScene() + "体验受影响";
    return "影响范围待补充";
  }

  private String actionTitle(Map<String, Object> item) {
    String category = stringValue(item.get("rootCauseTag"));
    return switch (category) {
      case "配置缺陷" -> "完善配置变更校验与回滚策略";
      case "流程断点" -> "补齐状态流转监控与异常兜底";
      case "监控缺失" -> "新增关键指标告警与复盘看板";
      case "数据异常" -> "建立数据一致性校验和导入复核机制";
      case "权限策略" -> "统一权限策略校验与灰度验证";
      default -> "沉淀处理路径并补充验证标准";
    };
  }

  private String deadline(Issue issue) {
    if (issue.getExpectedFinishTime() != null) return DATE.format(
      issue.getExpectedFinishTime()
    );
    int days = switch (value(issue.getPriority(), "P2")) {
      case "P0" -> 1;
      case "P1" -> 2;
      case "P2" -> 4;
      default -> 7;
    };
    LocalDateTime base = Optional.ofNullable(issue.getUpdatedAt()).orElse(
      LocalDateTime.now()
    );
    return DATE.format(base.plusDays(days));
  }

  private int overdueDays(Issue issue, LocalDateTime now) {
    if (
      issue.getExpectedFinishTime() == null ||
      issue.getActualFinishTime() != null ||
      isCompleted(issue)
    ) return 0;
    if (!issue.getExpectedFinishTime().isBefore(now)) return 0;
    return (int) Math.max(
      1,
      Duration.between(issue.getExpectedFinishTime(), now).toDays()
    );
  }

  private boolean isCompleted(Issue issue) {
    return "已完成".equals(issue.getStatus()) || issue.getActualFinishTime() != null;
  }

  private boolean isRelated(Issue base, Issue target) {
    return Objects.equals(base.getIssueType(), target.getIssueType()) ||
    Objects.equals(base.getBusinessScene(), target.getBusinessScene()) ||
    Objects.equals(causeCategory(base), causeCategory(target));
  }

  private boolean after(LocalDateTime time, LocalDateTime threshold) {
    return time != null && !time.isBefore(threshold);
  }

  private boolean containsAny(String text, String... parts) {
    if (text == null) return false;
    for (String part : parts) {
      if (text.contains(part)) return true;
    }
    return false;
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list
      .stream()
      .map(this::stringValue)
      .filter(text -> !text.isBlank())
      .toList();
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      return value == null ? null : Long.parseLong(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }

  private int intValue(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return 0;
    }
  }

  private String time(LocalDateTime value) {
    return value == null ? null : value.toString();
  }

  private String value(String value, String fallback) {
    return isBlank(value) ? fallback : value.trim();
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      result.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return result;
  }
}
