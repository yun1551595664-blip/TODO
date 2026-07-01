package com.company.issueops.service;

import com.company.issueops.domain.*;
import com.company.issueops.repository.*;
import com.company.issueops.web.IssueRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueService {

  private static final List<String> WORKFLOW_STATUSES = List.of(
    "待处理",
    "处理中",
    "待验证",
    "已完成"
  );

  private final IssueRepository issues;
  private final IssueLogRepository logs;
  private final AiClient aiClient;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher events;

  public Page<Issue> list(Map<String, String> q, Pageable page) {
    Specification<Issue> spec = (root, cq, cb) -> {
      List<Predicate> p = new ArrayList<>();
      p.add(cb.isFalse(root.get("deleted")));
      String kw = q.get("keyword");
      if (kw != null && !kw.isBlank()) p.add(
        cb.or(
          cb.like(root.get("title"), "%" + kw + "%"),
          cb.like(root.get("issueNo"), "%" + kw + "%")
        )
      );
      for (String k : List.of(
        "source",
        "businessScene",
        "issueType",
        "impactScope",
        "status",
        "priority",
        "responsibleDepartment"
      ))
        if (q.get(k) != null && !q.get(k).isBlank()) p.add(
          cb.equal(root.get(k), q.get(k))
        );
      if ("true".equals(q.get("reopened"))) p.add(
        cb.isTrue(root.get("reopened"))
      );
      if ("false".equals(q.get("reopened"))) p.add(
        cb.isFalse(root.get("reopened"))
      );
      if (
        q.get("createdStart") != null && !q.get("createdStart").isBlank()
      ) p.add(
        cb.greaterThanOrEqualTo(
          root.get("createdAt"),
          LocalDate.parse(q.get("createdStart")).atStartOfDay()
        )
      );
      if (q.get("createdEnd") != null && !q.get("createdEnd").isBlank()) p.add(
        cb.lessThan(
          root.get("createdAt"),
          LocalDate.parse(q.get("createdEnd")).plusDays(1).atStartOfDay()
        )
      );
      if ("true".equals(q.get("overdue"))) p.add(
        cb.and(
          cb.lessThan(root.get("expectedFinishTime"), LocalDateTime.now()),
          root.get("status").in("待处理", "处理中", "待验证")
        )
      );
      if ("false".equals(q.get("overdue"))) p.add(
        cb.or(
          cb.isNull(root.get("expectedFinishTime")),
          cb.greaterThanOrEqualTo(
            root.get("expectedFinishTime"),
            LocalDateTime.now()
          ),
          cb.equal(root.get("status"), "已完成")
        )
      );
      return cb.and(p.toArray(Predicate[]::new));
    };
    return issues.findAll(spec, page);
  }

  public Issue get(Long id) {
    return issues
      .findById(id)
      .filter(i -> !i.getDeleted())
      .orElseThrow(() -> new NoSuchElementException("问题不存在"));
  }

  @Transactional
  public Issue create(IssueRequest r) {
    Issue i = new Issue();
    copy(r, i);
    i.setIssueNo(nextIssueNo());
    if (i.getStatus() == null) i.setStatus("待处理");
    if (i.getPriority() == null) i.setPriority("P2");
    i = issues.save(i);
    addLog(i, "创建问题", "创建问题并进入待处理", "系统用户");
    return i;
  }

  @Transactional
  public Issue update(Long id, IssueRequest r) {
    Issue i = get(id);
    copy(r, i);
    Issue saved = issues.save(i);
    publishIssueChanged(saved);
    return saved;
  }

  @Transactional
  public void delete(Long id) {
    Issue i = get(id);
    i.setDeleted(true);
    issues.save(i);
    publishIssueChanged(i);
  }

  @Transactional
  public Issue status(Long id, String status, String operator, String content) {
    if (!WORKFLOW_STATUSES.contains(status)) throw new IllegalArgumentException(
      "无效状态，请使用：" + String.join("、", WORKFLOW_STATUSES)
    );
    Issue i = get(id);
    String old = i.getStatus();
    validateStatusTransition(old, status);
    i.setStatus(status);
    if (
      "已完成".equals(status) && !"已完成".equals(old) && i.getActualFinishTime() == null
    ) i.setActualFinishTime(LocalDateTime.now());
    if (!"已完成".equals(status) && "已完成".equals(old)) i.setActualFinishTime(
      null
    );
    issues.save(i);
    addLog(
      i,
      "状态变更",
      content == null ? old + " → " + status : content,
      operator
    );
    return i;
  }

  @Transactional
  public Issue reopened(
    Long id,
    boolean reopened,
    String reason,
    String operator
  ) {
    Issue issue = get(id);
    issue.setReopened(reopened);
    issue.setReopenedReason(reopened ? reason : null);
    if (reopened && "已完成".equals(issue.getStatus())) {
      issue.setStatus("处理中");
      issue.setActualFinishTime(null);
    }
    issues.save(issue);
    addLog(
      issue,
      reopened ? "复发标记" : "取消复发",
      reopened
        ? Optional.ofNullable(reason)
          .filter(s -> !s.isBlank())
          .orElse("问题再次发生，重新进入处理流程")
        : "确认当前问题不属于复发",
      operator
    );
    return issue;
  }

  @Transactional
  public IssueLog addLog(
    Issue i,
    String type,
    String content,
    String operator
  ) {
    IssueLog l = new IssueLog();
    l.setIssue(i);
    l.setActionType(type);
    l.setContent(content);
    l.setOperator(operator == null ? "系统用户" : operator);
    IssueLog saved = logs.save(l);
    publishIssueChanged(i);
    return saved;
  }

  public Map<String, Object> dashboard() {
    return dashboardStatistics();
  }

  public Map<String, Object> dashboardStatistics() {
    List<Issue> all = issues
      .findAll()
      .stream()
      .filter(i -> !i.getDeleted())
      .toList();
    LocalDateTime month = LocalDate.now().withDayOfMonth(1).atStartOfDay();
    Map<String, Long> status = all
      .stream()
      .collect(Collectors.groupingBy(Issue::getStatus, Collectors.counting()));
    long completed = status.getOrDefault("已完成", 0L);
    long reopened = all.stream().filter(i -> Boolean.TRUE.equals(i.getReopened())).count();
    long overdue = all.stream().filter(this::isOverdue).count();
    LocalDateTime updatedAt = all
      .stream()
      .map(Issue::getUpdatedAt)
      .filter(Objects::nonNull)
      .max(LocalDateTime::compareTo)
      .orElse(LocalDateTime.now());
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("total", (long) all.size());
    r.put("pending", status.getOrDefault("待处理", 0L));
    r.put("processing", status.getOrDefault("处理中", 0L));
    r.put("verifying", status.getOrDefault("待验证", 0L));
    r.put("completed", completed);
    r.put("reopened", reopened);
    r.put("overdue", overdue);
    r.put("updatedAt", updatedAt.toString());
    r.put(
      "monthlyNew",
      all
        .stream()
        .filter(
          i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(month)
        )
        .count()
    );
    r.put(
      "monthlyCompleted",
      all
        .stream()
        .filter(
          i ->
            i.getActualFinishTime() != null &&
            i.getActualFinishTime().isAfter(month)
        )
        .count()
    );
    return r;
  }

  public List<Map<String, Object>> dashboardTrend(String range) {
    List<Issue> all = issues
      .findAll()
      .stream()
      .filter(i -> !i.getDeleted())
      .toList();
    return buildTrend(all, range);
  }

  public Map<String, Object> dashboardAiInsight() {
    List<Issue> all = issues
      .findAll()
      .stream()
      .filter(i -> !i.getDeleted())
      .toList();
    LocalDateTime recentStart = LocalDate.now().minusDays(30).atStartOfDay();
    List<Issue> recent = all
      .stream()
      .filter(
        i -> i.getCreatedAt() == null || !i.getCreatedAt().isBefore(recentStart)
      )
      .toList();
    List<Issue> scope = recent.isEmpty() ? all : recent;
    int analyzedCount = scope.size();
    int base = Math.max(1, analyzedCount);

    List<Map<String, Object>> rootClusters = scope
      .stream()
      .collect(Collectors.groupingBy(this::clusterName))
      .entrySet()
      .stream()
      .map(e -> {
        List<Issue> group = e.getValue();
        long reopened = group
          .stream()
          .filter(i -> Boolean.TRUE.equals(i.getReopened()))
          .count();
        long overdue = group.stream().filter(this::isOverdue).count();
        String owner = mostFrequent(
          group,
          Issue::getResponsibleDepartment,
          "未分配"
        );
        return Map.<String, Object>of(
          "name",
          e.getKey(),
          "percent",
          Math.round(group.size() * 100.0 / base),
          "issueCount",
          (long) group.size(),
          "reopenedCount",
          reopened,
          "overdueCount",
          overdue,
          "owner",
          owner,
          "driver",
          buildClusterDriver(group, overdue, reopened)
        );
      })
      .sorted((a, b) -> {
        int byCount = Long.compare(
          (Long) b.get("issueCount"),
          (Long) a.get("issueCount")
        );
        return byCount != 0
          ? byCount
          : Long.compare(
            (Long) b.get("reopenedCount"),
            (Long) a.get("reopenedCount")
          );
      })
      .limit(3)
      .toList();

    long reopenedCount = all
      .stream()
      .filter(i -> Boolean.TRUE.equals(i.getReopened()))
      .count();
    long overdueCount = all.stream().filter(this::isOverdue).count();
    long highPriorityOpen = all
      .stream()
      .filter(
        i ->
          i.getActualFinishTime() == null &&
          ("P0".equals(i.getPriority()) || "P1".equals(i.getPriority()))
      )
      .count();
    String riskLevel = overdueCount > 0 || highPriorityOpen > 0
      ? "高"
      : reopenedCount > 0
        ? "中"
        : "低";

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("summaryText", buildAiSummary(rootClusters, overdueCount, reopenedCount));
    result.put("riskLevel", riskLevel);
    result.put("analyzedCount", analyzedCount);
    result.put("rootClusters", rootClusters);
    result.put(
      "actions",
      buildAiActions(rootClusters, overdueCount, reopenedCount, highPriorityOpen)
    );
    result.put(
      "signals",
      buildAiSignals(overdueCount, reopenedCount, highPriorityOpen)
    );
    result.put(
      "promptSuggestions",
      List.of("本周最需要关注什么？", "哪些问题可能复发？", "如何降低超期风险？")
    );
    result.put("updatedAt", LocalDateTime.now().toString());
    boolean aiApplied = false;
    if (aiClient.available()) {
      Optional<Map<String, Object>> generated = aiClient.chatJson(
        dashboardInsightSystemPrompt(),
        dashboardInsightUserPrompt(result)
      );
      if (generated.isPresent()) {
        applyDashboardAiInsight(result, generated.get());
        aiApplied = true;
      }
    }
    result.put("generatedBy", aiApplied ? aiClient.provider() : "local-rules");
    result.put("model", aiApplied ? aiClient.model() : "local-rules");
    return result;
  }

  public Map<String, Object> dashboardAiQuery(String question) {
    Map<String, Object> insight = dashboardAiInsight();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> clusters = (List<Map<String, Object>>) insight.get(
      "rootClusters"
    );
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> actions = (List<Map<String, Object>>) insight.get(
      "actions"
    );
    String q = Optional.ofNullable(question).orElse("").trim();
    String topCluster = clusters.isEmpty()
      ? "当前问题"
      : String.valueOf(clusters.get(0).get("name"));
    String firstAction = actions.isEmpty()
      ? "先补齐问题归因、验证证据和责任人"
      : String.valueOf(actions.get(0).get("title"));
    Optional<String> aiAnswer = aiClient.available()
      ? aiClient.chatText(
        dashboardQuestionSystemPrompt(),
        dashboardQuestionUserPrompt(insight, q)
      )
      : Optional.empty();
    String answer;
    if (aiAnswer.isPresent()) {
      answer = aiAnswer.get();
    } else if (q.contains("超期") || q.toLowerCase(Locale.ROOT).contains("sla")) {
      answer =
        "当前超期风险主要来自高优先级问题推进不稳定。建议把 P0/P1 问题纳入每日站会，预计完成时间前 24 小时触发升级，并要求责任部门补充阻塞原因。";
    } else if (q.contains("复发") || q.contains("重复")) {
      answer =
        "复发风险需要重点看同一业务场景下重复出现的问题。建议把已复发问题关联原始记录、修复版本和验证结果，形成可追溯闭环，避免只处理单点工单。";
    } else if (q.contains("建议") || q.contains("优化")) {
      answer = "建议优先执行：" + firstAction + "。这类动作能直接减少重复沟通、遗漏验证和跨部门等待。";
    } else {
      answer =
        "从当前数据看，最需要关注「" +
        topCluster +
        "」。建议先确认责任部门、复现证据和预计完成时间，再跟进是否存在复发或超期风险。";
    }
    return Map.of(
      "question",
      q,
      "answer",
      answer,
      "relatedQuestions",
      List.of("还有哪些类似问题？", "责任部门应该怎么拆？", "如何验证修复有效？"),
      "generatedBy",
      aiAnswer.isPresent() ? aiClient.provider() : "local-rules",
      "model",
      aiAnswer.isPresent() ? aiClient.model() : "local-rules",
      "generatedAt",
      LocalDateTime.now().toString()
    );
  }

  private List<Map<String, Object>> buildTrend(List<Issue> all, String range) {
    String normalizedRange = Optional.ofNullable(range).orElse("8w");
    boolean daily = "30d".equals(normalizedRange);
    int bucketCount = switch (normalizedRange) {
      case "12w" -> 12;
      case "30d" -> 30;
      default -> 8;
    };

    LocalDate trendAnchor = LocalDate.now();
    List<Map<String, Object>> trend = new ArrayList<>();
    for (int offset = bucketCount - 1; offset >= 0; offset--) {
      LocalDate bucketStart = daily
        ? trendAnchor.minusDays(offset)
        : trendAnchor.minusWeeks(offset);
      LocalDate bucketEnd = daily
        ? bucketStart.plusDays(1)
        : bucketStart.plusWeeks(1);
      trend.add(
        Map.of(
          "date",
          bucketStart.toString(),
          "新增",
          all
            .stream()
            .filter(
              i ->
                i.getCreatedAt() != null &&
                !i.getCreatedAt().toLocalDate().isBefore(bucketStart) &&
                i.getCreatedAt().toLocalDate().isBefore(bucketEnd)
            )
            .count(),
          "完成",
          all
            .stream()
            .filter(
              i ->
                i.getActualFinishTime() != null &&
                !i.getActualFinishTime().toLocalDate().isBefore(bucketStart) &&
                i.getActualFinishTime().toLocalDate().isBefore(bucketEnd)
            )
            .count(),
          "待处理",
          all
            .stream()
            .filter(
              i ->
                "待处理".equals(i.getStatus()) &&
                i.getCreatedAt() != null &&
                i.getCreatedAt().toLocalDate().isBefore(bucketEnd)
            )
            .count()
        )
      );
    }
    return trend;
  }

  public Map<String, Object> report() {
    List<Issue> all = issues
      .findAll()
      .stream()
      .filter(i -> !i.getDeleted())
      .toList();
    List<Map<String, Object>> types = all
      .stream()
      .collect(
        Collectors.groupingBy(
          i -> Optional.ofNullable(i.getIssueType()).orElse("其他"),
          Collectors.counting()
        )
      )
      .entrySet()
      .stream()
      .map(e ->
        Map.<String, Object>of("name", e.getKey(), "value", e.getValue())
      )
      .sorted((a, b) ->
        Long.compare((Long) b.get("value"), (Long) a.get("value"))
      )
      .toList();
    List<Map<String, Object>> deps = all
      .stream()
      .collect(
        Collectors.groupingBy(
          i ->
            Optional.ofNullable(i.getResponsibleDepartment()).orElse("未分配"),
          Collectors.counting()
        )
      )
      .entrySet()
      .stream()
      .map(e ->
        Map.<String, Object>of("name", e.getKey(), "value", e.getValue())
      )
      .sorted((a, b) ->
        Long.compare((Long) b.get("value"), (Long) a.get("value"))
      )
      .toList();
    Map<String, List<Issue>> clusters = all
      .stream()
      .collect(Collectors.groupingBy(this::clusterName));
    List<Map<String, Object>> topIssues = clusters
      .entrySet()
      .stream()
      .map(e -> {
        long reopened = e
          .getValue()
          .stream()
          .filter(i -> Boolean.TRUE.equals(i.getReopened()))
          .count();
        return Map.<String, Object>of(
          "name",
          e.getKey(),
          "value",
          (long) e.getValue().size(),
          "reopened",
          reopened
        );
      })
      .sorted((a, b) -> {
        int byCount = Long.compare(
          (Long) b.get("value"),
          (Long) a.get("value")
        );
        return byCount != 0
          ? byCount
          : Long.compare((Long) b.get("reopened"), (Long) a.get("reopened"));
      })
      .limit(10)
      .toList();
    Set<Long> duplicateIssueIds = clusters
      .values()
      .stream()
      .filter(group -> group.size() > 1)
      .flatMap(Collection::stream)
      .map(Issue::getId)
      .filter(Objects::nonNull)
      .collect(Collectors.toSet());
    all
      .stream()
      .filter(i -> Boolean.TRUE.equals(i.getReopened()) && i.getId() != null)
      .map(Issue::getId)
      .forEach(duplicateIssueIds::add);
    double hours = all
      .stream()
      .filter(i -> i.getActualFinishTime() != null && i.getCreatedAt() != null)
      .mapToLong(i ->
        Duration.between(i.getCreatedAt(), i.getActualFinishTime()).toHours()
      )
      .average()
      .orElse(0);
    List<Issue> overdueIssues = list(
      Map.of("overdue", "true"),
      PageRequest.of(0, 10)
    ).getContent();
    String topType = types.isEmpty()
      ? "高频问题"
      : String.valueOf(types.getFirst().get("name"));
    String topDepartment = deps.isEmpty()
      ? "责任部门"
      : String.valueOf(deps.getFirst().get("name"));
    List<Map<String, String>> suggestions = List.of(
      Map.of(
        "title",
        "为“" + topType + "”建立专项回归与发布门禁",
        "description",
        "该类型是当前主要问题类型之一，建议补齐核心场景自动化用例并纳入发布检查。",
        "owner",
        "产品部 / 技术部",
        "expectedImpact",
        "预计降低同类问题复发率 30%"
      ),
      Map.of(
        "title",
        "建立超期问题分级预警和升级机制",
        "description",
        "当前存在 " +
          overdueIssues.size() +
          " 个超期问题，建议按 P0/P1 设置自动升级。",
        "owner",
        topDepartment,
        "expectedImpact",
        "预计缩短平均处理时长 20%"
      ),
      Map.of(
        "title",
        "复发问题进入月度根因复盘",
        "description",
        "将重复问题关联原问题、修复版本和验证证据，沉淀可检索知识。",
        "owner",
        "产品运营",
        "expectedImpact",
        "提升闭环质量与知识复用率"
      )
    );
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("topIssues", topIssues);
    result.put("duplicateCount", duplicateIssueIds.size());
    result.put("typeDistribution", types);
    result.put("departmentDistribution", deps);
    result.put("averageHandleHours", Math.round(hours * 10) / 10.0);
    result.put("overdueIssues", overdueIssues);
    result.put("suggestions", suggestions);
    return result;
  }

  private String clusterName(Issue issue) {
    String title = Optional.ofNullable(issue.getTitle()).orElse("");
    for (String keyword : List.of(
      "支付",
      "优惠券",
      "导入",
      "推送",
      "报表",
      "登录",
      "权限",
      "订单"
    ))
      if (title.contains(keyword)) return keyword + "相关问题";
    String scene = Optional.ofNullable(issue.getBusinessScene()).orElse(
      "其他场景"
    );
    String type = Optional.ofNullable(issue.getIssueType()).orElse("其他问题");
    return scene + " · " + type;
  }

  private boolean isOverdue(Issue issue) {
    return (
      issue.getExpectedFinishTime() != null &&
      issue.getExpectedFinishTime().isBefore(LocalDateTime.now()) &&
      issue.getActualFinishTime() == null
    );
  }

  private String buildClusterDriver(
    List<Issue> group,
    long overdue,
    long reopened
  ) {
    String issueType = mostFrequent(group, Issue::getIssueType, "未分类问题");
    String department = mostFrequent(
      group,
      Issue::getResponsibleDepartment,
      "未分配部门"
    );
    if (reopened > 0) return "存在复发记录，建议补充回归验证和发布后观察";
    if (overdue > 0) return "存在超期风险，建议升级责任部门：" + department;
    return "主要集中在「" + issueType + "」，建议沉淀标准排查路径";
  }

  private String buildAiSummary(
    List<Map<String, Object>> rootClusters,
    long overdueCount,
    long reopenedCount
  ) {
    if (rootClusters.isEmpty()) return "暂无足够问题数据，建议先补充问题类型、责任部门和处理记录。";
    String topCluster = String.valueOf(rootClusters.get(0).get("name"));
    return (
      "近 30 天问题主要集中在「" +
      topCluster +
      "」，当前存在 " +
      overdueCount +
      " 个超期风险、" +
      reopenedCount +
      " 个复发风险。"
    );
  }

  private List<Map<String, Object>> buildAiActions(
    List<Map<String, Object>> rootClusters,
    long overdueCount,
    long reopenedCount,
    long highPriorityOpen
  ) {
    List<Map<String, Object>> actions = new ArrayList<>();
    String topCluster = rootClusters.isEmpty()
      ? "高频问题"
      : String.valueOf(rootClusters.get(0).get("name"));
    String owner = rootClusters.isEmpty()
      ? "产品运营"
      : String.valueOf(rootClusters.get(0).get("owner"));
    actions.add(
      Map.of(
        "priority",
        "1",
        "title",
        "围绕「" + topCluster + "」建立专项治理",
        "description",
        "补齐复现路径、根因标签和验收标准，避免同类问题反复进入 TAPD 后只做单点修复。",
        "owner",
        owner,
        "expectedImpact",
        "预计减少 25% 同类问题"
      )
    );
    if (overdueCount > 0 || highPriorityOpen > 0) {
      actions.add(
        Map.of(
          "priority",
          "2",
          "title",
          "建立 P0/P1 超期预警和升级机制",
          "description",
          "预计完成时间前 24 小时自动提醒，超期后要求责任部门补充阻塞原因和下一步计划。",
          "owner",
          "技术负责人",
          "expectedImpact",
          "预计缩短 20% 平均处理时长"
        )
      );
    }
    if (reopenedCount > 0) {
      actions.add(
        Map.of(
          "priority",
          "3",
          "title",
          "复发问题进入月度根因复盘",
          "description",
          "关联原问题、修复版本、验证证据和复发说明，沉淀可复用的验收清单。",
          "owner",
          "产品运营",
          "expectedImpact",
          "降低复发问题占比"
        )
      );
    }
    actions.add(
      Map.of(
        "priority",
        String.valueOf(actions.size() + 1),
        "title",
        "完善问题字段质量与处理记录规范",
        "description",
        "要求新增问题必须填写业务场景、影响范围、责任人和预计完成时间，方便后续归因分析。",
        "owner",
        "客服负责人",
        "expectedImpact",
        "提升问题闭环透明度"
      )
    );
    return actions.stream().limit(3).toList();
  }

  private List<Map<String, Object>> buildAiSignals(
    long overdueCount,
    long reopenedCount,
    long highPriorityOpen
  ) {
    return List.of(
      Map.of(
        "label",
        "超期风险",
        "value",
        overdueCount + " 个",
        "tone",
        overdueCount > 0 ? "warning" : "positive",
        "note",
        overdueCount > 0 ? "需要升级跟进" : "当前可控"
      ),
      Map.of(
        "label",
        "复发风险",
        "value",
        reopenedCount + " 个",
        "tone",
        reopenedCount > 0 ? "danger" : "positive",
        "note",
        reopenedCount > 0 ? "需要复盘验证" : "暂无明显复发"
      ),
      Map.of(
        "label",
        "高优先级推进",
        "value",
        highPriorityOpen + " 个",
        "tone",
        highPriorityOpen > 0 ? "warning" : "positive",
        "note",
        highPriorityOpen > 0 ? "关注责任人进展" : "无阻塞信号"
      )
    );
  }

  private String dashboardInsightSystemPrompt() {
    return "你是公司内部产品与业务问题治理专家。你只输出严格 JSON，不要 Markdown，不要解释。";
  }

  private String dashboardInsightUserPrompt(Map<String, Object> context) {
    return (
      """
      基于以下问题看板数据，生成首页 AI 智能洞察。
      要求：
      1. summaryText：一句 40 字以内的经营视角摘要。
      2. actions：3 条行动建议，每条包含 priority、title、description、owner、expectedImpact。
      3. promptSuggestions：3 个运营负责人会继续追问的问题。
      4. 不要编造不存在的数量，只能基于输入数据归纳。
      5. 返回 JSON 结构：
      {
        "summaryText": "...",
        "actions": [{"priority":"1","title":"...","description":"...","owner":"...","expectedImpact":"..."}],
        "promptSuggestions": ["..."]
      }

      输入数据：
      """
    ) + toJson(context);
  }

  private String dashboardQuestionSystemPrompt() {
    return "你是公司内部产品与业务问题治理助手。回答要具体、克制、可执行，最多 120 字。";
  }

  private String dashboardQuestionUserPrompt(
    Map<String, Object> context,
    String question
  ) {
    return (
      "用户问题：" +
      question +
      "\n\n当前看板洞察上下文：" +
      toJson(context) +
      "\n\n请直接回答，不要输出 Markdown 标题。"
    );
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return "{}";
    }
  }

  private void applyDashboardAiInsight(
    Map<String, Object> result,
    Map<String, Object> generated
  ) {
    Object summary = generated.get("summaryText");
    if (summary instanceof String text && !text.isBlank()) {
      result.put("summaryText", text.trim());
    }
    List<Map<String, Object>> fallbackActions = castMapList(result.get("actions"));
    List<Map<String, Object>> generatedActions = castMapList(generated.get("actions"));
    if (!generatedActions.isEmpty()) {
      result.put("actions", normalizeAiActions(generatedActions, fallbackActions));
    }
    Object promptSuggestions = generated.get("promptSuggestions");
    if (promptSuggestions instanceof List<?> list) {
      List<String> normalized = list
        .stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .limit(3)
        .toList();
      if (!normalized.isEmpty()) result.put("promptSuggestions", normalized);
    }
  }

  private List<Map<String, Object>> normalizeAiActions(
    List<Map<String, Object>> generatedActions,
    List<Map<String, Object>> fallbackActions
  ) {
    List<Map<String, Object>> normalized = new ArrayList<>();
    for (int index = 0; index < generatedActions.size() && index < 3; index++) {
      Map<String, Object> generated = generatedActions.get(index);
      Map<String, Object> fallback = index < fallbackActions.size()
        ? fallbackActions.get(index)
        : Map.of();
      normalized.add(
        Map.of(
          "priority",
          nonBlank(generated.get("priority"), String.valueOf(index + 1)),
          "title",
          nonBlank(generated.get("title"), nonBlank(fallback.get("title"), "补充问题治理动作")),
          "description",
          nonBlank(
            generated.get("description"),
            nonBlank(fallback.get("description"), "补齐问题归因、责任人和验收标准。")
          ),
          "owner",
          nonBlank(generated.get("owner"), nonBlank(fallback.get("owner"), "产品运营")),
          "expectedImpact",
          nonBlank(
            generated.get("expectedImpact"),
            nonBlank(fallback.get("expectedImpact"), "提升问题闭环质量")
          )
        )
      );
    }
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castMapList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list
      .stream()
      .filter(Map.class::isInstance)
      .map(item -> (Map<String, Object>) item)
      .toList();
  }

  private String nonBlank(Object value, String fallback) {
    if (value == null) return fallback;
    String text = String.valueOf(value).trim();
    return text.isBlank() ? fallback : text;
  }

  private String mostFrequent(
    List<Issue> issues,
    Function<Issue, String> mapper,
    String fallback
  ) {
    return issues
      .stream()
      .map(mapper)
      .filter(value -> value != null && !value.isBlank())
      .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
      .entrySet()
      .stream()
      .max(Map.Entry.comparingByValue())
      .map(Map.Entry::getKey)
      .orElse(fallback);
  }

  private void copy(IssueRequest r, Issue i) {
    BeanUtils.copyProperties(
      r,
      i,
      "issueNo",
      "createdAt",
      "updatedAt",
      "deleted",
      "logs"
    );
  }

  private synchronized String nextIssueNo() {
    String prefix =
      "PBI-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
    int next = issues
      .findTopByIssueNoStartingWithOrderByIssueNoDesc(prefix)
      .map(Issue::getIssueNo)
      .map(no -> no.substring(prefix.length()))
      .flatMap(this::parsePositiveInt)
      .orElse(0) +
    1;
    return prefix + String.format("%04d", next);
  }

  private Optional<Integer> parsePositiveInt(String value) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? Optional.of(parsed) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private void validateStatusTransition(String oldStatus, String nextStatus) {
    if (oldStatus == null || Objects.equals(oldStatus, nextStatus)) return;
    Map<String, Set<String>> allowed = Map.of(
      "待处理",
      Set.of("处理中"),
      "处理中",
      Set.of("待处理", "待验证", "已完成"),
      "待验证",
      Set.of("处理中", "已完成"),
      "已完成",
      Set.of("处理中", "待验证")
    );
    if (!allowed.getOrDefault(oldStatus, Set.of()).contains(nextStatus)) {
      throw new IllegalArgumentException(
        "非法状态流转：" + oldStatus + " → " + nextStatus
      );
    }
  }

  private void publishIssueChanged(Issue issue) {
    events.publishEvent(new IssueChangedEvent(issue.getId()));
  }
}
