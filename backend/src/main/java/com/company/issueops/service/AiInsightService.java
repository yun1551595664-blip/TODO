package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class AiInsightService {

  private static final String DEFAULT_QUESTION = "帮我判断哪些问题需要今天跟进";
  private static final Set<String> VALID_RISK_LEVELS = Set.of(
    "高风险",
    "中风险",
    "低风险"
  );

  private final IssueRepository issues;
  private final AiClient aiClient;
  private final AiActionService aiActionService;
  private final AiInsightSessionStore sessionStore;
  private final ObjectMapper objectMapper;
  private final AtomicLong insightSequence = new AtomicLong();

  private volatile Map<String, Object> latestInsight;

  @TransactionalEventListener
  public void onIssueChanged(IssueChangedEvent event) {
    invalidate();
  }

  public void invalidate() {
    latestInsight = null;
  }

  public Map<String, Object> overview() {
    Map<String, Object> cached = latestInsight;
    if (cached != null) return cached;
    return refresh();
  }

  public synchronized Map<String, Object> refresh() {
    List<Issue> issueList = loadIssues();
    Map<String, Object> local = buildLocalOverview(issueList);
    Map<String, Object> ruleAnalysis = buildRuleAnalysis(local);
    local.put("ruleAnalysis", ruleAnalysis);
    local.put("aiAnalysis", buildAiAnalysis(null, false));
    local.put("finalView", buildFinalView(local));
    markAiPending(local);
    latestInsight = local;
    return local;
  }

  public synchronized Map<String, Object> aiAnalysis() {
    Map<String, Object> local = latestInsight != null
      ? new LinkedHashMap<>(latestInsight)
      : buildLocalOverview(loadIssues());
    local.putIfAbsent("ruleAnalysis", buildRuleAnalysis(local));

    Optional<Map<String, Object>> generated = aiClient.available()
      ? aiClient.chatJson(systemPrompt(), overviewUserPrompt(local))
      : Optional.empty();

    boolean aiApplied = false;
    if (generated.isPresent()) {
      applyAiOverview(local, generated.get());
      aiApplied = true;
    }

    local.put("aiAnalysis", buildAiAnalysis(generated.orElse(null), aiApplied));
    local.put("finalView", buildFinalView(local));
    markAiResult(local, aiApplied);
    latestInsight = local;
    return local;
  }

  public Map<String, Object> chat(
    String question,
    String insightId,
    Map<String, Object> context
  ) {
    String normalizedQuestion = Optional
      .ofNullable(question)
      .map(String::trim)
      .filter(value -> !value.isBlank())
      .orElse(DEFAULT_QUESTION);
    Map<String, Object> current = latestInsight != null ? latestInsight : refresh();

    Map<String, Object> interactionContext = normalizeInteractionContext(
      context,
      current
    );
    Optional<Map<String, Object>> generated = aiClient.available()
      ? aiClient.chatJson(
        systemPrompt(),
        chatUserPrompt(normalizedQuestion, current, interactionContext)
      )
      : Optional.empty();

    Map<String, Object> answer = generated
      .map(value -> normalizeChatAnswer(normalizedQuestion, value, current))
      .orElseGet(() -> localChatAnswer(normalizedQuestion, current));
    answer.put("insightId", current.get("insightId"));
    answer.put("generatedBy", generated.isPresent() ? aiClient.provider() : "local-rules");
    answer.put("model", generated.isPresent() ? aiClient.model() : "local-rules");
    answer.put("generatedAt", LocalDateTime.now().toString());
    if (generated.isEmpty()) answer.put("aiError", "AI 分析暂不可用，当前展示本地规则回答");
    return answer;
  }

  public Map<String, Object> createSession(Map<String, Object> body) {
    return sessionStore.createSession(
      stringValue(body.get("insightId")),
      value(stringValue(body.get("title")), "AI 智能洞察对话")
    );
  }

  public List<Map<String, Object>> sessionMessages(String sessionId) {
    return sessionStore.messages(sessionId);
  }

  public SseEmitter streamChat(String sessionId, Map<String, Object> body) {
    SseEmitter emitter = new SseEmitter(120_000L);
    Thread.startVirtualThread(() -> runStreamChat(sessionId, body, emitter));
    return emitter;
  }

  private void runStreamChat(
    String sessionId,
    Map<String, Object> body,
    SseEmitter emitter
  ) {
    String normalizedQuestion = Optional
      .ofNullable(stringValue(body.get("question")))
      .map(String::trim)
      .filter(value -> !value.isBlank())
      .orElse(DEFAULT_QUESTION);
    Map<String, Object> current = latestInsight != null ? latestInsight : refresh();
    Map<String, Object> session = sessionStore.ensureSession(
      sessionId,
      stringValue(current.get("insightId"))
    );
    String normalizedSessionId = stringValue(session.get("sessionId"));
    Map<String, Object> interactionContext = normalizeInteractionContext(body, current);

    try {
      sendEvent(emitter, "session", session);
      sessionStore.addUserMessage(normalizedSessionId, normalizedQuestion);
      sendThinking(emitter);

      Map<String, Object> answer;
      if (isLowValueQuestion(normalizedQuestion)) {
        answer = localGuardAnswer(normalizedQuestion, current);
        emitAnswerAsChunks(emitter, answer, current);
      } else if (!aiClient.available()) {
        answer = localChatAnswer(normalizedQuestion, current);
        emitAnswerAsChunks(emitter, answer, current);
      } else if (hasWriteIntent(normalizedQuestion)) {
        answer = chat(normalizedQuestion, stringValue(current.get("insightId")), body);
        emitAnswerAsChunks(emitter, answer, current);
      } else {
        Optional<String> streamed = aiClient.chatStream(
          systemPrompt(),
          streamChatUserPrompt(
            normalizedQuestion,
            current,
            interactionContext,
            sessionStore.recentMessages(normalizedSessionId, 8)
          ),
          delta -> sendEvent(emitter, "delta", map("text", delta))
        );
        answer = streamed
          .map(text -> streamedChatAnswer(normalizedQuestion, text, current))
          .orElseGet(() -> {
            Map<String, Object> fallback = localChatAnswer(normalizedQuestion, current);
            fallback.put("aiError", "AI 流式分析暂不可用，当前展示本地规则回答");
            return fallback;
          });
        if (streamed.isEmpty()) emitAnswerAsChunks(emitter, answer, current);
      }

      answer.putIfAbsent("insightId", current.get("insightId"));
      answer.putIfAbsent("generatedBy", aiClient.available() ? aiClient.provider() : "local-rules");
      answer.putIfAbsent("model", aiClient.available() ? aiClient.model() : "local-rules");
      answer.putIfAbsent("generatedAt", LocalDateTime.now().toString());
      answer.put("sessionId", normalizedSessionId);
      sessionStore.addAssistantMessage(
        normalizedSessionId,
        stringValue(answer.get("answer")),
        answer,
        stringValue(answer.get("model")),
        stringValue(answer.get("generatedBy"))
      );
      if (answer.get("pendingAction") != null) {
        sendEvent(emitter, "action", answer.get("pendingAction"));
      }
      sendEvent(emitter, "answer", answer);
      sendEvent(
        emitter,
        "done",
        map(
          "sessionId",
          normalizedSessionId,
          "generatedBy",
          answer.get("generatedBy"),
          "model",
          answer.get("model")
        )
      );
      emitter.complete();
    } catch (Exception e) {
      try {
        sendEvent(emitter, "error", map("message", e.getMessage()));
      } catch (Exception ignored) {}
      emitter.complete();
    }
  }

  private List<Issue> loadIssues() {
    return issues
      .findAll()
      .stream()
      .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
      .toList();
  }

  private Map<String, Object> buildLocalOverview(List<Issue> issueList) {
    LocalDateTime now = LocalDateTime.now();
    List<Map<String, Object>> issueContexts = issueList
      .stream()
      .map(issue -> buildIssueContext(issue, now))
      .sorted(
        Comparator
          .<Map<String, Object>>comparingInt(item -> number(item.get("score")))
          .reversed()
      )
      .toList();

    long overdueCount = issueContexts
      .stream()
      .filter(item -> number(item.get("overdueDays")) > 0)
      .count();
    long reopenedCount = issueContexts
      .stream()
      .filter(item -> number(item.get("repeatCount")) > 0)
      .count();
    long highPriorityCount = issueContexts
      .stream()
      .filter(item ->
        "P0".equals(item.get("priority")) || "P1".equals(item.get("priority"))
      )
      .count();
    String riskLevel = riskLevel(overdueCount, reopenedCount, highPriorityCount);
    List<Map<String, Object>> priorityIssues = issueContexts
      .stream()
      .limit(6)
      .map(this::toPriorityIssue)
      .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("insightId", nextInsightId());
    result.put("period", "近 30 天");
    result.put("totalIssues", issueContexts.size());
    result.put("updatedAt", now.toString());
    result.put("riskLevel", riskLevel);
    result.put(
      "summary",
      summaryText(riskLevel, overdueCount, reopenedCount, highPriorityCount)
    );
    result.put(
      "riskRadar",
      List.of(
        riskItem(
          "overdue",
          "超期问题",
          overdueCount,
          "需要升级跟进",
          "warning",
          "clock"
        ),
        riskItem(
          "reopened",
          "复发问题",
          reopenedCount,
          "需要复盘验证",
          "danger",
          "repeat"
        ),
        riskItem(
          "highPriority",
          "P0/P1 问题",
          highPriorityCount,
          "优先推进",
          "primary",
          "priority"
        )
      )
    );
    result.put("priorityIssues", priorityIssues);
    result.put("aiReply", defaultAiReply(result));
    result.put("suggestedActions", defaultSuggestedActions(priorityIssues));
    result.put("issueContext", issueContexts);
    return result;
  }

  private Map<String, Object> buildIssueContext(Issue issue, LocalDateTime now) {
    int overdueDays = overdueDays(issue, now);
    int repeatCount = Boolean.TRUE.equals(issue.getReopened()) ? 1 : 0;
    int score = issueScore(issue, overdueDays, repeatCount);
    List<String> evidenceTags = evidenceTags(issue, overdueDays, repeatCount);
    Set<String> filters = new LinkedHashSet<>();
    if (overdueDays > 0) filters.add("overdue");
    if (repeatCount > 0) filters.add("reopened");
    if ("P0".equals(issue.getPriority()) || "P1".equals(issue.getPriority())) {
      filters.add("highPriority");
    }

    return map(
      "id",
      issue.getId(),
      "issueId",
      String.valueOf(issue.getId()),
      "issueNo",
      value(issue.getIssueNo(), "PBI-" + issue.getId()),
      "title",
      value(issue.getTitle(), "未命名问题"),
      "priority",
      value(issue.getPriority(), "P2"),
      "status",
      value(issue.getStatus(), "待处理"),
      "department",
      value(issue.getResponsibleDepartment(), "未分配"),
      "owner",
      value(issue.getResponsiblePerson(), "未分配"),
      "overdueDays",
      overdueDays,
      "repeatCount",
      repeatCount,
      "impact",
      impact(issue),
      "expectedImpact",
      expectedImpact(issue),
      "reason",
      localPriorityReason(issue, overdueDays, repeatCount),
      "evidenceTags",
      evidenceTags,
      "filters",
      new ArrayList<>(filters),
      "score",
      score,
      "createdAt",
      issue.getCreatedAt(),
      "updatedAt",
      issue.getUpdatedAt()
    );
  }

  private Map<String, Object> toPriorityIssue(Map<String, Object> item) {
    Map<String, Object> copy = new LinkedHashMap<>(item);
    copy.put("rank", 0);
    copy.put("evidence", item.get("evidenceTags"));
    return copy;
  }

  private Map<String, Object> riskItem(
    String key,
    String label,
    long value,
    String description,
    String tone,
    String icon
  ) {
    return map(
      "key",
      key,
      "label",
      label,
      "value",
      value,
      "description",
      description,
      "tone",
      tone,
      "icon",
      icon
    );
  }

  private int issueScore(Issue issue, int overdueDays, int repeatCount) {
    int score = switch (value(issue.getPriority(), "P2")) {
      case "P0" -> 60;
      case "P1" -> 44;
      case "P2" -> 26;
      default -> 14;
    };
    String status = value(issue.getStatus(), "待处理");
    if ("待处理".equals(status)) score += 14;
    if ("处理中".equals(status)) score += 10;
    if ("待验证".equals(status)) score += 4;
    if ("已完成".equals(status)) score -= 16;
    score += Math.min(32, Math.max(0, overdueDays) * 4);
    score += repeatCount * 24;
    if (!"未说明影响范围".equals(impact(issue))) score += 4;
    return score;
  }

  private int overdueDays(Issue issue, LocalDateTime now) {
    if (
      issue.getExpectedFinishTime() == null ||
      issue.getActualFinishTime() != null ||
      "已完成".equals(issue.getStatus())
    ) {
      return 0;
    }
    if (!issue.getExpectedFinishTime().isBefore(now)) return 0;
    return (int) Math.max(
      1,
      Duration.between(issue.getExpectedFinishTime(), now).toDays()
    );
  }

  private List<String> evidenceTags(
    Issue issue,
    int overdueDays,
    int repeatCount
  ) {
    List<String> tags = new ArrayList<>();
    tags.add(value(issue.getPriority(), "P2"));
    tags.add(value(issue.getStatus(), "待处理"));
    if (overdueDays > 0) tags.add("超期 " + overdueDays + " 天");
    if (repeatCount > 0) tags.add("复发 " + repeatCount + " 次");
    if (!isBlank(issue.getImpactScope())) tags.add(issue.getImpactScope());
    if (!isBlank(issue.getBusinessScene())) tags.add(issue.getBusinessScene());
    return tags.stream().distinct().limit(5).toList();
  }

  private String riskLevel(
    long overdueCount,
    long reopenedCount,
    long highPriorityCount
  ) {
    if (overdueCount > 0 || reopenedCount > 0 || highPriorityCount >= 3) {
      return "高风险";
    }
    if (highPriorityCount > 0) return "中风险";
    return "低风险";
  }

  private String impact(Issue issue) {
    if (!isBlank(issue.getCustomerImpact())) return issue
      .getCustomerImpact()
      .trim();
    if (!isBlank(issue.getImpactScope())) return "影响" + issue.getImpactScope();
    return "未说明影响范围";
  }

  private String expectedImpact(Issue issue) {
    String impact = impact(issue);
    if (!"未说明影响范围".equals(impact)) return impact;
    String scene = value(issue.getBusinessScene(), "当前业务");
    return "减少" + scene + "异常反馈，提升问题闭环效率";
  }

  private String localPriorityReason(
    Issue issue,
    int overdueDays,
    int repeatCount
  ) {
    List<String> reasons = new ArrayList<>();
    if ("P0".equals(issue.getPriority()) || "P1".equals(issue.getPriority())) {
      reasons.add("高优先级");
    }
    if (overdueDays > 0) reasons.add("已超期 " + overdueDays + " 天");
    if (repeatCount > 0) reasons.add("存在复发记录");
    if (reasons.isEmpty()) reasons.add("影响范围需要持续观察");
    return String.join("，", reasons);
  }

  private String summaryText(
    String riskLevel,
    long overdueCount,
    long reopenedCount,
    long highPriorityCount
  ) {
    return (
      "当前综合判断为" +
      riskLevel +
      "，其中超期 " +
      overdueCount +
      " 个、复发 " +
      reopenedCount +
      " 个、P0/P1 " +
      highPriorityCount +
      " 个。"
    );
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> defaultAiReply(Map<String, Object> overview) {
    List<Map<String, Object>> priorityIssues = (List<Map<String, Object>>) overview.get(
      "priorityIssues"
    );
    List<String> issueTitles = priorityIssues
      .stream()
      .limit(3)
      .map(item -> String.valueOf(item.get("title")))
      .toList();
    return map(
      "question",
      DEFAULT_QUESTION,
      "judgmentBasis",
      List.of(
        overview.get("summary"),
        "优先级由 P 级、超期天数、复发标记、当前状态和影响范围共同计算。",
        issueTitles.isEmpty()
          ? "当前数据不足，无法判断具体问题。"
          : "本次优先聚焦：" + String.join("、", issueTitles)
      ),
      "recommendedPriority",
      issueTitles.isEmpty() ? "当前数据不足，无法判断" : String.join(" → ", issueTitles),
      "impactScope",
      collectImpactScope(priorityIssues),
      "processingOrder",
      issueTitles
    );
  }

  private String collectImpactScope(List<Map<String, Object>> priorityIssues) {
    List<String> scopes = priorityIssues
      .stream()
      .map(item -> String.valueOf(item.get("impact")))
      .filter(value -> !isBlank(value) && !"未说明影响范围".equals(value))
      .distinct()
      .limit(3)
      .toList();
    return scopes.isEmpty() ? "当前数据不足，无法判断影响范围" : String.join("；", scopes);
  }

  private List<String> defaultSuggestedActions(List<Map<String, Object>> priorityIssues) {
    List<String> actions = new ArrayList<>();
    if (!priorityIssues.isEmpty()) {
      actions.add("先确认排名靠前问题的责任部门、预计完成时间和阻塞点。");
    }
    boolean hasOverdue = priorityIssues
      .stream()
      .anyMatch(item -> number(item.get("overdueDays")) > 0);
    boolean hasReopened = priorityIssues
      .stream()
      .anyMatch(item -> number(item.get("repeatCount")) > 0);
    if (hasOverdue) actions.add("对超期问题建立当日升级机制。");
    if (hasReopened) actions.add("对复发问题补充复盘结论和验证证据。");
    if (actions.isEmpty()) actions.add("当前数据不足，建议先补充影响范围与处理记录。");
    return actions.stream().limit(4).toList();
  }

  private Map<String, Object> buildRuleAnalysis(Map<String, Object> local) {
    return map(
      "riskLevel",
      local.get("riskLevel"),
      "riskRadar",
      local.get("riskRadar"),
      "priorityIssues",
      local.get("priorityIssues"),
      "summary",
      local.get("summary"),
      "suggestedActions",
      local.get("suggestedActions"),
      "issueContext",
      local.get("issueContext"),
      "source",
      "local-rules"
    );
  }

  private Map<String, Object> buildAiAnalysis(
    Map<String, Object> generated,
    boolean aiApplied
  ) {
    return map(
      "available",
      aiClient.available(),
      "applied",
      aiApplied,
      "provider",
      aiApplied ? aiClient.provider() : "local-rules",
      "model",
      aiApplied ? aiClient.model() : "local-rules",
      "summary",
      generated == null ? "" : stringValue(generated.get("summary")),
      "riskLevel",
      generated == null ? "" : stringValue(generated.get("riskLevel")),
      "aiReply",
      generated == null ? Map.of() : generated.get("aiReply"),
      "suggestedActions",
      generated == null ? List.of() : stringList(generated.get("suggestedActions"), 6)
    );
  }

  @SuppressWarnings("unchecked")
  private void markAiPending(Map<String, Object> local) {
    Map<String, Object> analysis = local.get("aiAnalysis") instanceof Map<?, ?> map
      ? new LinkedHashMap<>((Map<String, Object>) map)
      : new LinkedHashMap<>();
    analysis.put("status", "pending");
    analysis.put("available", aiClient.available());
    analysis.put("applied", false);
    analysis.put("provider", aiClient.available() ? aiClient.provider() : "local-rules");
    analysis.put("model", aiClient.available() ? aiClient.model() : "local-rules");
    analysis.remove("error");
    analysis.remove("failure");

    local.put("aiAnalysis", analysis);
    local.put("aiStatus", "pending");
    local.put("fallback", map("used", false, "reason", "规则数据已就绪，AI 分析正在后台加载"));
    local.remove("aiError");
    local.put("aiFailure", Map.of());
    local.put("generatedBy", "local-rules");
    local.put("aiAvailable", aiClient.available());
    local.put(
      "modelInfo",
      map(
        "provider",
        aiClient.available() ? aiClient.provider() : "local-rules",
        "model",
        aiClient.available() ? aiClient.model() : "local-rules"
      )
    );
  }

  @SuppressWarnings("unchecked")
  private void markAiResult(Map<String, Object> local, boolean aiApplied) {
    AiFailure failure = aiApplied
      ? AiFailure.none()
      : aiClient.available()
        ? aiClient.lastFailure()
        : new AiFailure("missing_api_key", "AI API Key 未配置");
    if (!aiApplied && (failure == null || !failure.present())) {
      failure = new AiFailure("unknown", "AI 分析失败，当前展示本地规则分析");
    }

    Map<String, Object> analysis = local.get("aiAnalysis") instanceof Map<?, ?> map
      ? new LinkedHashMap<>((Map<String, Object>) map)
      : new LinkedHashMap<>();
    analysis.put("status", aiApplied ? "applied" : "failed");
    analysis.put("available", aiClient.available());
    analysis.put("applied", aiApplied);
    analysis.put("provider", aiApplied ? aiClient.provider() : "local-rules");
    analysis.put("model", aiApplied ? aiClient.model() : "local-rules");
    if (aiApplied) {
      analysis.remove("error");
      analysis.remove("failure");
    } else {
      analysis.put("error", aiFailureMessage(failure));
      analysis.put("failure", aiFailureMap(failure));
    }

    local.put("aiAnalysis", analysis);
    local.put("aiStatus", aiApplied ? "applied" : "failed");
    local.put("fallback", map("used", !aiApplied, "reason", aiApplied ? null : aiFailureMessage(failure)));
    local.put("generatedBy", aiApplied ? aiClient.provider() : "local-rules");
    local.put("aiAvailable", aiClient.available());
    if (aiApplied) {
      local.remove("aiError");
      local.put("aiFailure", Map.of());
    } else {
      local.put("aiError", aiFailureMessage(failure));
      local.put("aiFailure", aiFailureMap(failure));
    }
    local.put(
      "modelInfo",
      map(
        "provider",
        aiApplied ? aiClient.provider() : aiClient.available() ? aiClient.provider() : "local-rules",
        "model",
        aiApplied ? aiClient.model() : aiClient.available() ? aiClient.model() : "local-rules"
      )
    );
  }

  private Map<String, Object> aiFailureMap(AiFailure failure) {
    if (failure == null || !failure.present()) return Map.of();
    return map("code", failure.code(), "message", aiFailureMessage(failure));
  }

  private String aiFailureMessage(AiFailure failure) {
    if (failure == null || isBlank(failure.message())) {
      return "AI 分析失败，当前展示本地规则分析";
    }
    String message = failure.message().trim();
    return message.contains("当前展示本地规则") || message.contains("已切换为本地规则")
      ? message
      : message + "，当前展示本地规则分析";
  }

  private Map<String, Object> buildFinalView(Map<String, Object> local) {
    return map(
      "riskLevel",
      local.get("riskLevel"),
      "riskRadar",
      local.get("riskRadar"),
      "priorityIssues",
      local.get("priorityIssues"),
      "summary",
      local.get("summary"),
      "aiReply",
      local.get("aiReply"),
      "suggestedActions",
      local.get("suggestedActions")
    );
  }

  private void applyAiOverview(
    Map<String, Object> local,
    Map<String, Object> generated
  ) {
    String summary = stringValue(generated.get("summary"));
    if (!isBlank(summary)) local.put("summary", summary);

    String riskLevel = stringValue(generated.get("riskLevel"));
    if (VALID_RISK_LEVELS.contains(riskLevel)) local.put("riskLevel", riskLevel);

    List<Map<String, Object>> normalizedPriority = normalizePriorityIssues(
      generated.get("priorityIssues"),
      local
    );
    if (!normalizedPriority.isEmpty()) {
      local.put("priorityIssues", normalizedPriority);
    }

    Map<String, Object> aiReply = normalizeAiReply(generated.get("aiReply"), local);
    local.put("aiReply", aiReply);

    List<String> suggestedActions = stringList(generated.get("suggestedActions"), 4);
    if (!suggestedActions.isEmpty()) local.put("suggestedActions", suggestedActions);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> normalizePriorityIssues(
    Object generatedIssues,
    Map<String, Object> local
  ) {
    if (!(generatedIssues instanceof List<?> list)) return List.of();
    List<Map<String, Object>> localIssues = (List<Map<String, Object>>) local.get(
      "priorityIssues"
    );
    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    Map<String, Map<String, Object>> byNo = new LinkedHashMap<>();
    Map<String, Map<String, Object>> byTitle = new LinkedHashMap<>();
    for (Map<String, Object> item : localIssues) {
      byId.put(String.valueOf(item.get("issueId")), item);
      byNo.put(String.valueOf(item.get("issueNo")), item);
      byTitle.put(String.valueOf(item.get("title")), item);
    }

    List<Map<String, Object>> normalized = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> generated)) continue;
      String issueId = stringValue(generated.get("issueId"));
      String title = stringValue(generated.get("title"));
      Map<String, Object> base = byId.get(issueId);
      if (base == null) base = byNo.get(issueId);
      if (base == null) base = byTitle.get(title);
      if (base == null) continue;
      String key = String.valueOf(base.get("issueId"));
      if (!used.add(key)) continue;

      Map<String, Object> item = new LinkedHashMap<>(base);
      item.put("rank", normalized.size() + 1);
      String reason = stringValue(generated.get("reason"));
      if (!isBlank(reason)) item.put("reason", reason);
      String expectedImpact = stringValue(generated.get("expectedImpact"));
      if (!isBlank(expectedImpact)) item.put("expectedImpact", expectedImpact);
      item.put("evidence", base.get("evidenceTags"));
      normalized.add(item);
    }

    for (Map<String, Object> localIssue : localIssues) {
      if (normalized.size() >= 6) break;
      String key = String.valueOf(localIssue.get("issueId"));
      if (!used.add(key)) continue;
      Map<String, Object> item = new LinkedHashMap<>(localIssue);
      item.put("rank", normalized.size() + 1);
      normalized.add(item);
    }
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeAiReply(
    Object aiReply,
    Map<String, Object> local
  ) {
    Map<String, Object> fallback = (Map<String, Object>) local.get("aiReply");
    Map<String, Object> normalized = new LinkedHashMap<>(fallback);
    if (aiReply instanceof String text && !text.isBlank()) {
      List<String> basis = new ArrayList<>(stringList(fallback.get("judgmentBasis"), 3));
      basis.add(0, text.trim());
      normalized.put("judgmentBasis", basis.stream().distinct().limit(4).toList());
      return normalized;
    }
    if (!(aiReply instanceof Map<?, ?> map)) return normalized;
    List<String> basis = stringList(map.get("judgmentBasis"), 4);
    if (!basis.isEmpty()) normalized.put("judgmentBasis", basis);
    String recommendedPriority = stringValue(map.get("recommendedPriority"));
    if (!isBlank(recommendedPriority)) {
      normalized.put("recommendedPriority", recommendedPriority);
    }
    String impactScope = stringValue(map.get("impactScope"));
    if (!isBlank(impactScope)) normalized.put("impactScope", impactScope);
    List<String> processingOrder = stringList(map.get("processingOrder"), 4);
    if (!processingOrder.isEmpty()) normalized.put("processingOrder", processingOrder);
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeChatAnswer(
    String question,
    Map<String, Object> generated,
    Map<String, Object> current
  ) {
    List<Map<String, Object>> relatedIssues = relatedIssues(
      generated.get("relatedIssues"),
      current,
      false
    );
    Optional<Map<String, Object>> pendingAction = normalizePendingActionForQuestion(
      question,
      generated.get("pendingAction"),
      current
    );
    String answer = stringValue(generated.get("answer"));
    if (isBlank(answer)) answer = String.valueOf(current.get("summary"));
    if (hasWriteIntent(question)) {
      answer = pendingAction.isPresent()
        ? "已生成待确认操作，尚未写入系统。请核对下方内容，确认无误后点击“确认执行”。"
        : "我没有识别到可安全执行的操作草案。请明确说明要新增的问题标题，或指定问题编号和目标状态。";
    }
    List<String> evidence = relatedIssues.isEmpty()
      ? stringList(generated.get("evidence"), 4)
      : localEvidence(relatedIssues, current);
    return map(
      "question",
      question,
      "answer",
      answer,
      "evidence",
      evidence,
      "suggestedActions",
      stringList(generated.get("suggestedActions"), 4),
      "relatedIssues",
      relatedIssues,
      "pendingAction",
      pendingAction.orElse(null)
    );
  }

  private Map<String, Object> localChatAnswer(
    String question,
    Map<String, Object> current
  ) {
    List<Map<String, Object>> relatedIssues = relatedIssues(null, current, true);
    String answer;
    String lower = question.toLowerCase(Locale.ROOT);
    if (question.contains("复发") || question.contains("重复")) {
      answer =
        "优先看已标记复发的问题。复发问题需要补齐原始问题、修复版本、验证证据和复发说明，避免只做单点修复。";
    } else if (question.contains("超期") || lower.contains("sla")) {
      answer =
        "优先处理已超期且仍未完成的问题。建议当天确认阻塞原因、责任人和下一步时间点，并建立升级机制。";
    } else {
      answer =
        "今天建议先跟进排名靠前的问题，原因是它们同时命中了高优先级、超期、复发或影响范围较大的信号。";
    }
    return map(
      "question",
      question,
      "answer",
      answer,
      "evidence",
      localEvidence(relatedIssues, current),
      "suggestedActions",
      current.get("suggestedActions"),
      "relatedIssues",
      relatedIssues,
      "pendingAction",
      null
    );
  }

  private Map<String, Object> localGuardAnswer(
    String question,
    Map<String, Object> current
  ) {
    List<Map<String, Object>> relatedIssues = relatedIssues(null, current, true);
    return map(
      "question",
      question,
      "answer",
      "我会聚焦当前问题数据做分析，不展开模型身份等闲聊。当前可以继续分析优先级、超期风险、客户影响、负责人拆分和处理计划。",
      "evidence",
      localEvidence(relatedIssues, current),
      "suggestedActions",
      List.of("改问：哪个问题最紧急？", "改问：按负责人拆分任务", "改问：生成本周处理建议"),
      "relatedIssues",
      relatedIssues.stream().limit(3).toList(),
      "pendingAction",
      null,
      "generatedBy",
      "local-rules",
      "model",
      "business-guard"
    );
  }

  private Map<String, Object> streamedChatAnswer(
    String question,
    String text,
    Map<String, Object> current
  ) {
    List<Map<String, Object>> relatedIssues = relatedIssues(null, current, true);
    return map(
      "question",
      question,
      "answer",
      text,
      "evidence",
      localEvidence(relatedIssues, current),
      "suggestedActions",
      current.get("suggestedActions"),
      "relatedIssues",
      relatedIssues,
      "pendingAction",
      null,
      "generatedBy",
      aiClient.provider(),
      "model",
      aiClient.model()
    );
  }

  private void sendThinking(SseEmitter emitter) throws Exception {
    for (String step : List.of(
      "正在读取问题数据",
      "正在分析超时与优先级",
      "正在判断影响范围",
      "正在生成处理建议"
    )) {
      sendEvent(emitter, "thinking", map("step", step));
      Thread.sleep(160);
    }
  }

  private void emitAnswerAsChunks(
    SseEmitter emitter,
    Map<String, Object> answer,
    Map<String, Object> current
  ) throws Exception {
    String text = answerDisplayText(answer, current);
    int step = Math.max(10, Math.ceilDiv(text.length(), 60));
    for (int index = 0; index < text.length(); index += step) {
      sendEvent(
        emitter,
        "delta",
        map("text", text.substring(index, Math.min(index + step, text.length())))
      );
      Thread.sleep(18);
    }
  }

  private String answerDisplayText(Map<String, Object> answer, Map<String, Object> current) {
    List<Map<String, Object>> related = relatedIssues(
      answer.get("relatedIssues"),
      current,
      true
    );
    return (
      "结论\n" +
      "- " +
      stringValue(answer.get("answer")) +
      "\n\n判断依据\n" +
      stringList(answer.get("evidence"), 4).stream()
        .map(item -> "- " + item)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- " + current.get("summary")) +
      "\n\n建议动作\n" +
      stringList(answer.get("suggestedActions"), 4).stream()
        .map(item -> "- " + item)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- 请先补齐责任部门、预计完成时间和影响范围。") +
      "\n\n建议负责人\n" +
      related.stream()
        .limit(4)
        .map(issue ->
          "- " +
          issue.get("title") +
          "：" +
          issue.get("department") +
          " / " +
          issue.get("owner")
        )
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- 当前数据不足，无法判断。")
    );
  }

  private void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
    emitter.send(SseEmitter.event().name(name).data(data));
  }

  private Optional<Map<String, Object>> normalizePendingActionForQuestion(
    String question,
    Object rawAction,
    Map<String, Object> current
  ) {
    Optional<Map<String, Object>> action = aiActionService.normalizePendingAction(
      rawAction
    );
    if (action.isEmpty()) return Optional.empty();
    String actionType = stringValue(action.get().get("actionType"));
    if (hasCreateIntent(question) && !"CREATE_ISSUE".equals(actionType)) {
      return Optional.empty();
    }
    if (hasStatusIntent(question) && !"UPDATE_STATUS".equals(actionType)) {
      return Optional.empty();
    }
    if (hasLogIntent(question) && !"ADD_LOG".equals(actionType)) {
      return Optional.empty();
    }
    return aiActionService.registerPendingAction(
      action.get(),
      stringValue(current.get("insightId"))
    );
  }

  private boolean hasWriteIntent(String question) {
    return hasCreateIntent(question) || hasStatusIntent(question) || hasLogIntent(question);
  }

  private boolean hasCreateIntent(String question) {
    return containsAny(question, "新增", "创建", "新建", "录入", "提报");
  }

  private boolean hasStatusIntent(String question) {
    return (
      containsAny(question, "状态改", "状态更新", "改成", "改为", "更新为", "置为") &&
      containsAny(question, "待处理", "处理中", "待验证", "已完成")
    );
  }

  private boolean hasLogIntent(String question) {
    return containsAny(question, "处理记录", "追加记录", "新增记录", "备注", "记录一下");
  }

  private boolean containsAny(String source, String... keywords) {
    if (source == null) return false;
    for (String keyword : keywords) {
      if (source.contains(keyword)) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> relatedIssues(
    Object generatedRelatedIssues,
    Map<String, Object> current,
    boolean defaultToPriorityIssues
  ) {
    List<Map<String, Object>> priorityIssues = (List<Map<String, Object>>) current.get(
      "priorityIssues"
    );
    if (!(generatedRelatedIssues instanceof List<?> list)) {
      return defaultToPriorityIssues
        ? priorityIssues.stream().limit(3).toList()
        : List.of();
    }
    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    Map<String, Map<String, Object>> byNo = new LinkedHashMap<>();
    for (Map<String, Object> item : priorityIssues) {
      byId.put(String.valueOf(item.get("issueId")), item);
      byNo.put(String.valueOf(item.get("issueNo")), item);
    }
    List<Map<String, Object>> related = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    for (Object value : list) {
      String key = String.valueOf(value);
      Map<String, Object> item = byId.get(key);
      if (item == null) item = byNo.get(key);
      if (item == null) continue;
      if (used.add(String.valueOf(item.get("issueId")))) related.add(item);
    }
    if (related.isEmpty()) {
      return defaultToPriorityIssues
        ? priorityIssues.stream().limit(3).toList()
        : List.of();
    }
    return related.stream().limit(4).toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeInteractionContext(
    Map<String, Object> requestContext,
    Map<String, Object> current
  ) {
    Map<String, Object> context = requestContext;
    Object nested = requestContext == null ? null : requestContext.get("context");
    if (nested instanceof Map<?, ?> nestedMap) {
      context = (Map<String, Object>) nestedMap;
    }

    String selectedRisk = context == null
      ? ""
      : stringValue(context.get("selectedRisk"));
    List<String> visibleIssueIds = context == null
      ? List.of()
      : stringList(context.get("visibleIssues"), 10);

    List<Map<String, Object>> visibleIssues = findIssuesByIdentifiers(
      visibleIssueIds,
      current
    );
    return map(
      "selectedRisk",
      selectedRisk,
      "selectedRiskLabel",
      riskLabel(selectedRisk, current),
      "visibleIssueIds",
      visibleIssueIds,
      "visibleIssues",
      visibleIssues
    );
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> findIssuesByIdentifiers(
    List<String> identifiers,
    Map<String, Object> current
  ) {
    if (identifiers == null || identifiers.isEmpty()) return List.of();
    List<Map<String, Object>> priorityIssues = (List<Map<String, Object>>) current.get(
      "priorityIssues"
    );
    Map<String, Map<String, Object>> index = new LinkedHashMap<>();
    for (Map<String, Object> issue : priorityIssues) {
      index.put(String.valueOf(issue.get("id")), issue);
      index.put(String.valueOf(issue.get("issueId")), issue);
      index.put(String.valueOf(issue.get("issueNo")), issue);
    }
    List<Map<String, Object>> matched = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    for (String identifier : identifiers) {
      Map<String, Object> issue = index.get(identifier);
      if (issue == null) continue;
      String key = String.valueOf(issue.get("issueId"));
      if (used.add(key)) matched.add(issue);
    }
    return matched;
  }

  @SuppressWarnings("unchecked")
  private String riskLabel(String selectedRisk, Map<String, Object> current) {
    if (isBlank(selectedRisk)) return "";
    Object riskRadar = current.get("riskRadar");
    if (!(riskRadar instanceof List<?> list)) return selectedRisk;
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> item)) continue;
      if (Objects.equals(selectedRisk, String.valueOf(item.get("key")))) {
        String label = stringValue(item.get("label"));
        return isBlank(label) ? selectedRisk : label;
      }
    }
    return selectedRisk;
  }

  private List<String> localEvidence(
    List<Map<String, Object>> relatedIssues,
    Map<String, Object> current
  ) {
    List<String> evidence = new ArrayList<>();
    evidence.add(String.valueOf(current.get("summary")));
    for (Map<String, Object> issue : relatedIssues) {
      evidence.add(
        issue.get("issueNo") +
        "：" +
        issue.get("title") +
        "，" +
        issue.get("reason")
      );
    }
    return evidence.stream().distinct().limit(5).toList();
  }

  private String systemPrompt() {
    return """
      你是一个企业级售后问题治理分析助手。你的任务不是闲聊，而是基于系统提供的问题数据，识别风险、归纳根因、判断优先级，并给出可执行的处理建议。
      当前产品是“产品与业务问题进度管理看板”，不是 PMS、Jira、TAPD 或其他外部系统。
      当前系统已支持：问题台账、新增问题、编辑问题、问题详情、状态流转、处理记录、复发标记、报表和 AI 洞察。
      当前 AI 洞察模块可以把用户对话解析为“待确认操作”，但不能直接执行写入动作；必须由用户确认后，系统才会新增问题、更新状态或新增处理记录。
      支持的待确认操作只有 CREATE_ISSUE、UPDATE_STATUS、ADD_LOG。不要生成删除、批量修改、改责任人、改优先级等未授权动作。
      你必须严格基于输入数据分析，不能编造不存在的问题、数量、责任人、责任部门、超期天数、复发次数或时间。
      AI 输出中的问题名称必须来自输入数据。责任部门必须来自输入数据。超期天数和复发次数不能自行编造。
      如果数据不足，必须说明“当前数据不足，无法判断”。
      输出必须是严格 JSON，不要 Markdown，不要代码块，不要额外解释。
      """;
  }

  private String overviewUserPrompt(Map<String, Object> local) {
    return (
      """
      请基于以下本地规则计算结果和问题明细，生成 AI 智能洞察。
      返回 JSON 格式：
      {
        "summary": "综合判断",
        "riskLevel": "高风险|中风险|低风险",
        "priorityIssues": [
          {
            "rank": 1,
            "issueId": "必须使用输入中的 issueId 或 issueNo",
            "title": "必须来自输入",
            "reason": "推荐优先处理的原因",
            "department": "必须来自输入",
            "expectedImpact": "预期影响",
            "evidence": ["只能引用输入中已有的证据"]
          }
        ],
        "aiReply": {
          "judgmentBasis": ["判断依据"],
          "recommendedPriority": "推荐优先级",
          "impactScope": "影响范围",
          "processingOrder": ["建议处理顺序"]
        },
        "suggestedActions": ["可执行动作"]
      }

      输入数据：
      """ +
      toJson(local)
    );
  }

  private String chatUserPrompt(
    String question,
    Map<String, Object> current,
    Map<String, Object> interactionContext
  ) {
    return (
      """
      用户追问：
      """ +
      question +
      """

      请只基于以下洞察和问题上下文回答。返回 JSON：
      {
        "answer": "简洁、具体、可执行的回答",
        "evidence": ["引用依据"],
        "suggestedActions": ["建议动作"],
        "relatedIssues": ["相关问题的 issueId 或 issueNo；如果追问是系统能力问题，可以返回空数组"],
        "pendingAction": {
          "actionType": "CREATE_ISSUE|UPDATE_STATUS|ADD_LOG",
          "title": "待确认操作标题",
          "summary": "这次操作会做什么",
          "payload": {
            "title": "CREATE_ISSUE 必填：问题标题",
            "description": "问题描述",
            "source": "问题来源，默认 AI 对话",
            "businessScene": "业务场景",
            "issueType": "问题类型",
            "impactScope": "影响范围",
            "customerImpact": "客户影响",
            "reproduceSteps": "复现步骤",
            "priority": "P0|P1|P2|P3",
            "status": "待处理|处理中|待验证|已完成",
            "responsibleDepartment": "责任部门",
            "responsiblePerson": "责任人",
            "issueId": "UPDATE_STATUS 或 ADD_LOG 必填：输入中真实存在的问题 id",
            "issueNo": "输入中真实存在的问题编号",
            "content": "状态变更说明或处理记录内容",
            "operator": "操作人，默认 AI 助理"
          }
        }
      }

      只有当用户明确要求“新增/创建/录入问题”、“把某个问题状态改为...”、“给某个问题追加处理记录/备注”时，才返回 pendingAction；否则 pendingAction 返回 null。
      如果用户明确说“新增/创建/新建/录入/提报问题”，必须生成 CREATE_ISSUE，严禁把它匹配为已有问题的 UPDATE_STATUS。
      如果返回 pendingAction，answer 必须使用“已生成待确认操作，尚未写入系统”的口吻，严禁说“已创建、已更新、已新增记录”。
      如果用户要求更新状态或新增处理记录但没有指出明确的问题，pendingAction 返回 null，并要求用户补充问题编号或标题。
      UPDATE_STATUS 和 ADD_LOG 的 issueId/issueNo 必须来自当前上下文中的真实问题。

      当前用户界面上下文：
      """ +
      toJson(interactionContext) +
      """

      如果 selectedRisk 或 visibleIssues 不为空，回答必须优先围绕当前筛选后的可见问题展开；不要把未显示的问题作为主要结论。

      当前上下文：
      """ +
      toJson(current)
    );
  }

  private String streamChatUserPrompt(
    String question,
    Map<String, Object> current,
    Map<String, Object> interactionContext,
    List<Map<String, Object>> history
  ) {
    return (
      """
      用户追问：
      """ +
      question +
      """

      请只基于以下问题数据和对话历史回答。输出给管理人员阅读的中文文本，不要输出 JSON，不要 Markdown 代码块。
      回答必须包含这些小标题：结论、判断依据、风险原因、建议动作、建议负责人、建议截止时间。
      每个判断必须引用输入中的真实问题标题、责任部门、负责人、状态、超期天数、复发次数或影响范围。
      不要回答模型身份、训练数据、供应商闲聊；遇到无业务价值问题时，把用户引导回当前问题治理数据。
      如果 selectedRisk 或 visibleIssues 不为空，必须优先围绕当前筛选后的可见问题展开。

      最近对话历史：
      """ +
      toJson(history) +
      """

      当前用户界面上下文：
      """ +
      toJson(interactionContext) +
      """

      当前问题洞察上下文：
      """ +
      toJson(current)
    );
  }

  private boolean isLowValueQuestion(String question) {
    String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
    boolean lowValue = containsAny(text, "你是什么模型", "什么模型", "你是谁", "model");
    boolean business = containsAny(
      text,
      "问题",
      "超期",
      "优先",
      "风险",
      "客户",
      "处理",
      "负责人",
      "复发",
      "状态",
      "计划",
      "汇报",
      "影响",
      "部门",
      "建议",
      "p0",
      "p1"
    );
    return lowValue && !business;
  }

  private String nextInsightId() {
    return (
      "AI-" +
      LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) +
      "-" +
      String.format("%04d", insightSequence.incrementAndGet())
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

  private int number(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return 0;
    }
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
