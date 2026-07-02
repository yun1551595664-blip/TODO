package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.domain.IssueLog;
import com.company.issueops.repository.IssueRepository;
import com.company.issueops.service.AuthService.AuthUser;
import com.company.issueops.web.IssueRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiActionService {

  private static final Set<String> ACTION_TYPES = Set.of(
    "CREATE_ISSUE",
    "UPDATE_STATUS",
    "ADD_LOG"
  );
  private static final Set<String> STATUSES = Set.of(
    "待处理",
    "处理中",
    "待验证",
    "已完成"
  );
  private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
  private static final Duration ACTION_TTL = Duration.ofMinutes(10);
  private static final int MAX_PENDING_ACTIONS = 200;

  private final IssueService issueService;
  private final IssueRepository issues;
  private final DataScopeService dataScopeService;
  private final ConcurrentMap<String, PendingAction> pendingActions = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> normalizePendingAction(Object rawAction) {
    return normalizePendingAction(null, rawAction);
  }

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> normalizePendingAction(
    AuthUser user,
    Object rawAction
  ) {
    if (!(rawAction instanceof Map<?, ?> raw)) return Optional.empty();
    String actionType = text(raw.get("actionType"));
    if (!ACTION_TYPES.contains(actionType)) return Optional.empty();

    Object rawPayload = raw.get("payload");
    if (!(rawPayload instanceof Map<?, ?> payload)) return Optional.empty();

    Map<String, Object> normalizedPayload = switch (actionType) {
      case "CREATE_ISSUE" -> normalizeCreatePayload((Map<String, Object>) payload);
      case "UPDATE_STATUS" -> normalizeStatusPayload(user, (Map<String, Object>) payload);
      case "ADD_LOG" -> normalizeLogPayload(user, (Map<String, Object>) payload);
      default -> Map.of();
    };
    if (normalizedPayload.isEmpty()) return Optional.empty();

    Map<String, Object> action = new LinkedHashMap<>();
    action.put("actionType", actionType);
    action.put("title", fallback(text(raw.get("title")), actionTitle(actionType)));
    action.put("summary", fallback(text(raw.get("summary")), actionSummary(actionType)));
    action.put("payload", normalizedPayload);
    action.put("warnings", warnings(actionType, normalizedPayload));
    action.put("requiresConfirmation", true);
    return Optional.of(action);
  }

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> registerPendingAction(
    Map<String, Object> normalizedAction,
    String insightId
  ) {
    return registerPendingAction(null, normalizedAction, insightId);
  }

  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> registerPendingAction(
    AuthUser user,
    Map<String, Object> normalizedAction,
    String insightId
  ) {
    cleanupExpired();
    if (pendingActions.size() >= MAX_PENDING_ACTIONS) {
      String oldestActionId = pendingActions
        .values()
        .stream()
        .min((left, right) -> left.createdAt().compareTo(right.createdAt()))
        .map(PendingAction::actionId)
        .orElse(null);
      if (oldestActionId != null) pendingActions.remove(oldestActionId);
    }

    Object rawPayload = normalizedAction.get("payload");
    if (!(rawPayload instanceof Map<?, ?> payload)) return Optional.empty();

    LocalDateTime now = LocalDateTime.now();
    String actionId = UUID.randomUUID().toString();
    String actionType = text(normalizedAction.get("actionType"));
    PendingAction pendingAction = new PendingAction(
      actionId,
      fallback(insightId, ""),
      actionType,
      new LinkedHashMap<>((Map<String, Object>) payload),
      dataScopeService.scopeKey(user),
      now,
      now.plus(ACTION_TTL)
    );
    pendingActions.put(actionId, pendingAction);

    Map<String, Object> preview = new LinkedHashMap<>(normalizedAction);
    preview.put("actionId", actionId);
    preview.put("expiresAt", pendingAction.expiresAt().toString());
    preview.put("expiresInSeconds", ACTION_TTL.toSeconds());
    return Optional.of(preview);
  }

  public Map<String, Object> execute(Map<String, Object> request) {
    return execute(null, request);
  }

  public Map<String, Object> execute(AuthUser user, Map<String, Object> request) {
    cleanupExpired();
    String actionId = text(request.get("actionId"));
    if (actionId.isBlank()) {
      throw new IllegalArgumentException("缺少待确认操作 ID，请先通过 AI 生成操作草案");
    }

    PendingAction pendingAction = pendingActions.remove(actionId);
    if (pendingAction == null) {
      throw new IllegalArgumentException("待确认操作不存在或已过期，请重新生成");
    }
    if (pendingAction.expiresAt().isBefore(LocalDateTime.now())) {
      throw new IllegalArgumentException("待确认操作已过期，请重新生成");
    }
    if (!Objects.equals(pendingAction.scopeKey(), dataScopeService.scopeKey(user))) {
      throw new IllegalArgumentException("该待确认操作不属于当前账号或数据范围，请重新生成");
    }

    return switch (pendingAction.actionType()) {
      case "CREATE_ISSUE" -> executeCreate(user, pendingAction.payload());
      case "UPDATE_STATUS" -> executeStatus(user, pendingAction.payload());
      case "ADD_LOG" -> executeLog(user, pendingAction.payload());
      default -> throw new IllegalArgumentException(
        "不支持的 AI 动作：" + pendingAction.actionType()
      );
    };
  }

  private Map<String, Object> normalizeCreatePayload(Map<String, Object> payload) {
    String title = text(payload.get("title"));
    if (title.isBlank()) return Map.of();
    String priority = normalizePriority(text(payload.get("priority")));
    String status = normalizeStatus(text(payload.get("status")));
    Map<String, Object> result = map(
      "title",
      title,
      "description",
      text(payload.get("description")),
      "source",
      fallback(text(payload.get("source")), "AI 对话"),
      "businessScene",
      text(payload.get("businessScene")),
      "issueType",
      text(payload.get("issueType")),
      "impactScope",
      text(payload.get("impactScope")),
      "customerImpact",
      text(payload.get("customerImpact")),
      "reproduceSteps",
      text(payload.get("reproduceSteps")),
      "priority",
      priority,
      "status",
      status,
      "responsibleDepartment",
      text(payload.get("responsibleDepartment")),
      "responsiblePerson",
      text(payload.get("responsiblePerson")),
      "createdBy",
      fallback(text(payload.get("createdBy")), "AI 助理")
    );
    if (!missingCreateFields(result).isEmpty()) return Map.of();
    return result;
  }

  private Map<String, Object> normalizeStatusPayload(AuthUser user, Map<String, Object> payload) {
    Optional<Issue> issue = findIssue(user, payload);
    if (issue.isEmpty()) return Map.of();
    String status = normalizeStatus(text(payload.get("status")));
    return map(
      "issueId",
      issue.get().getId(),
      "issueNo",
      issueNo(issue.get()),
      "title",
      issue.get().getTitle(),
      "status",
      status,
      "operator",
      fallback(text(payload.get("operator")), "AI 助理"),
      "content",
      fallback(text(payload.get("content")), "AI 对话确认后更新状态为：" + status)
    );
  }

  private Map<String, Object> normalizeLogPayload(AuthUser user, Map<String, Object> payload) {
    Optional<Issue> issue = findIssue(user, payload);
    if (issue.isEmpty()) return Map.of();
    String content = text(payload.get("content"));
    if (content.isBlank()) return Map.of();
    return map(
      "issueId",
      issue.get().getId(),
      "issueNo",
      issueNo(issue.get()),
      "title",
      issue.get().getTitle(),
      "actionType",
      fallback(text(payload.get("actionType")), "处理记录"),
      "content",
      content,
      "operator",
      fallback(text(payload.get("operator")), "AI 助理")
    );
  }

  private Map<String, Object> executeCreate(AuthUser user, Map<String, Object> payload) {
    Map<String, Object> normalized = normalizeCreatePayload(payload);
    if (normalized.isEmpty()) throw new IllegalArgumentException(
      "新增问题缺少必要字段：标题、业务场景、问题类型、影响范围、责任部门"
    );
    Issue issue = issueService.create(
      new IssueRequest(
        text(normalized.get("title")),
        emptyToNull(normalized.get("description")),
        emptyToNull(normalized.get("source")),
        emptyToNull(normalized.get("businessScene")),
        emptyToNull(normalized.get("issueType")),
        emptyToNull(normalized.get("impactScope")),
        emptyToNull(normalized.get("customerImpact")),
        emptyToNull(normalized.get("reproduceSteps")),
        text(normalized.get("priority")),
        text(normalized.get("status")),
        emptyToNull(normalized.get("responsibleDepartment")),
        emptyToNull(normalized.get("responsiblePerson")),
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        user == null ? text(normalized.get("createdBy")) : user.displayName()
      )
    );
    return executed("CREATE_ISSUE", "已创建问题：" + issue.getIssueNo(), issue);
  }

  private Map<String, Object> executeStatus(AuthUser user, Map<String, Object> payload) {
    Map<String, Object> normalized = normalizeStatusPayload(user, payload);
    if (normalized.isEmpty()) throw new NoSuchElementException("未找到要更新状态的问题");
    Issue issue = issueService.status(
      user,
      number(normalized.get("issueId")),
      text(normalized.get("status")),
      text(normalized.get("operator")),
      text(normalized.get("content"))
    );
    return executed("UPDATE_STATUS", "已更新状态：" + issue.getStatus(), issue);
  }

  private Map<String, Object> executeLog(AuthUser user, Map<String, Object> payload) {
    Map<String, Object> normalized = normalizeLogPayload(user, payload);
    if (normalized.isEmpty()) throw new IllegalArgumentException("新增处理记录缺少问题或内容");
    Issue issue = issueService.get(user, number(normalized.get("issueId")));
    IssueLog log = issueService.addLog(
      issue,
      text(normalized.get("actionType")),
      text(normalized.get("content")),
      text(normalized.get("operator"))
    );
    return map(
      "executed",
      true,
      "actionType",
      "ADD_LOG",
      "message",
      "已新增处理记录",
      "issue",
      issue,
      "logId",
      log.getId(),
      "executedAt",
      LocalDateTime.now().toString()
    );
  }

  private Optional<Issue> findIssue(AuthUser user, Map<String, Object> payload) {
    Long id = nullableNumber(payload.get("issueId"));
    if (id == null) id = nullableNumber(payload.get("id"));
    if (id != null) {
      Long finalId = id;
      return issues
        .findById(finalId)
        .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
        .filter(issue -> user == null || canSee(user, issue));
    }
    String issueNo = fallback(text(payload.get("issueNo")), text(payload.get("issueId")));
    if (issueNo.isBlank()) return Optional.empty();
    return issues
      .findAll()
      .stream()
      .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
      .filter(issue -> user == null || canSee(user, issue))
      .filter(issue -> Objects.equals(issueNo, issue.getIssueNo()))
      .findFirst();
  }

  private boolean canSee(AuthUser user, Issue issue) {
    try {
      issueService.get(user, issue.getId());
      return true;
    } catch (NoSuchElementException ignored) {
      return false;
    }
  }

  private Map<String, Object> executed(String actionType, String message, Issue issue) {
    return map(
      "executed",
      true,
      "actionType",
      actionType,
      "message",
      message,
      "issue",
      issue,
      "executedAt",
      LocalDateTime.now().toString()
    );
  }

  private List<String> warnings(String actionType, Map<String, Object> payload) {
    Set<String> warnings = new LinkedHashSet<>();
    warnings.add("AI 只生成操作草案，确认后才会写入系统；操作有效期为 10 分钟。");
    if ("CREATE_ISSUE".equals(actionType)) {
      warnings.add("请确认标题、优先级、责任部门和影响范围是否准确。");
    }
    if ("UPDATE_STATUS".equals(actionType)) {
      warnings.add("状态变更会自动写入处理记录。");
    }
    if ("ADD_LOG".equals(actionType)) {
      warnings.add("处理记录会追加到问题详情时间线。");
    }
    return List.copyOf(warnings);
  }

  private String actionTitle(String actionType) {
    return switch (actionType) {
      case "CREATE_ISSUE" -> "创建新问题";
      case "UPDATE_STATUS" -> "更新问题状态";
      case "ADD_LOG" -> "新增处理记录";
      default -> "待确认操作";
    };
  }

  private String actionSummary(String actionType) {
    return switch (actionType) {
      case "CREATE_ISSUE" -> "AI 已根据对话整理出新问题草案。";
      case "UPDATE_STATUS" -> "AI 已根据对话整理出状态变更草案。";
      case "ADD_LOG" -> "AI 已根据对话整理出处理记录草案。";
      default -> "请确认后执行。";
    };
  }

  private String normalizeStatus(String value) {
    return STATUSES.contains(value) ? value : "待处理";
  }

  private String normalizePriority(String value) {
    return PRIORITIES.contains(value) ? value : "P2";
  }

  private List<String> missingCreateFields(Map<String, Object> payload) {
    List<String> missing = new java.util.ArrayList<>();
    if (text(payload.get("title")).isBlank()) missing.add("标题");
    if (text(payload.get("businessScene")).isBlank()) missing.add("业务场景");
    if (text(payload.get("issueType")).isBlank()) missing.add("问题类型");
    if (text(payload.get("impactScope")).isBlank()) missing.add("影响范围");
    if (text(payload.get("responsibleDepartment")).isBlank()) missing.add("责任部门");
    return missing;
  }

  private Long number(Object value) {
    Long number = nullableNumber(value);
    if (number == null) throw new IllegalArgumentException("缺少问题 ID");
    return number;
  }

  private Long nullableNumber(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      String text = text(value);
      return text.isBlank() ? null : Long.parseLong(text);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String emptyToNull(Object value) {
    String text = text(value);
    return text.isBlank() ? null : text;
  }

  private String issueNo(Issue issue) {
    return fallback(issue.getIssueNo(), "PBI-" + issue.getId());
  }

  private String fallback(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private Map<String, Object> map(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      map.put(String.valueOf(values[i]), values[i + 1]);
    }
    return map;
  }

  private void cleanupExpired() {
    LocalDateTime now = LocalDateTime.now();
    pendingActions
      .entrySet()
      .removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  private record PendingAction(
    String actionId,
    String insightId,
    String actionType,
    Map<String, Object> payload,
    String scopeKey,
    LocalDateTime createdAt,
    LocalDateTime expiresAt
  ) {}
}
