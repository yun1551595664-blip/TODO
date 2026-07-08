package com.company.issueops.service;

import com.company.issueops.domain.*;
import com.company.issueops.repository.*;
import com.company.issueops.service.AuthService.AuthUser;
import com.company.issueops.web.IssueRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

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
  private final DataScopeService dataScopeService;

  private record ReportPeriod(
    LocalDate start,
    LocalDate end,
    LocalDate previousStart,
    LocalDate previousEnd
  ) {}

  private record AnalysisMetrics(
    long total,
    long newIssues,
    long completed,
    long pending,
    long overdue,
    long reopened,
    long highPriority,
    double slaRate,
    double overdueRate,
    double reopenedRate,
    double averageHandleHours,
    int governanceScore,
    int closureScore,
    int overdueScore,
    int reopenedScore,
    int responseScore
  ) {}

  private LocalDate today() {
    return LocalDate.now(BUSINESS_ZONE);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }

  private String nowWithOffset() {
    return OffsetDateTime.now(BUSINESS_ZONE).toString();
  }

  private String withBusinessOffset(LocalDateTime value) {
    return value.atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  public Page<Issue> list(Map<String, String> q, Pageable page) {
    return list(null, q, page);
  }

  public Page<Issue> list(AuthUser user, Map<String, String> q, Pageable page) {
    Specification<Issue> spec = (root, cq, cb) -> {
      List<Predicate> p = new ArrayList<>();
      p.add(activePredicate(root, cb));
      if (user != null) p.add(dataScopeService.visibleIssuePredicate(user, root, cb));
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
          cb.lessThan(root.get("expectedFinishTime"), now()),
          root.get("status").in("待处理", "处理中", "待验证")
        )
      );
      if ("false".equals(q.get("overdue"))) p.add(
        cb.or(
          cb.isNull(root.get("expectedFinishTime")),
          cb.greaterThanOrEqualTo(
            root.get("expectedFinishTime"),
            now()
          ),
          cb.equal(root.get("status"), "已完成")
        )
      );
      return cb.and(p.toArray(Predicate[]::new));
    };
    return issues.findAll(spec, page);
  }

  public Issue get(Long id) {
    return get(null, id);
  }

  public Issue get(AuthUser user, Long id) {
    return issues
      .findById(id)
      .filter(this::isActive)
      .filter(i -> user == null || dataScopeService.canSee(user, i))
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
    return update(null, id, r);
  }

  @Transactional
  public Issue update(AuthUser user, Long id, IssueRequest r) {
    Issue i = get(user, id);
    String createdBy = i.getCreatedBy();
    copy(r, i);
    i.setCreatedBy(createdBy);
    Issue saved = issues.save(i);
    publishIssueChanged(saved);
    return saved;
  }

  @Transactional
  public void delete(Long id) {
    delete(null, id);
  }

  @Transactional
  public void delete(AuthUser user, Long id) {
    Issue i = get(user, id);
    i.setDeleted(true);
    issues.save(i);
    publishIssueChanged(i);
  }

  @Transactional
  public Issue status(Long id, String status, String operator, String content) {
    return status(null, id, status, operator, content);
  }

  @Transactional
  public Issue status(
    AuthUser user,
    Long id,
    String status,
    String operator,
    String content
  ) {
    if (!WORKFLOW_STATUSES.contains(status)) throw new IllegalArgumentException(
      "无效状态，请使用：" + String.join("、", WORKFLOW_STATUSES)
    );
    Issue i = get(user, id);
    String old = i.getStatus();
    validateStatusTransition(old, status);
    i.setStatus(status);
    if (
      "已完成".equals(status) && !"已完成".equals(old) && i.getActualFinishTime() == null
    ) i.setActualFinishTime(now());
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
    return reopened(null, id, reopened, reason, operator);
  }

  @Transactional
  public Issue reopened(
    AuthUser user,
    Long id,
    boolean reopened,
    String reason,
    String operator
  ) {
    Issue issue = get(user, id);
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
    return dashboardStatistics(null);
  }

  public Map<String, Object> dashboardStatistics(AuthUser user) {
    List<Issue> all = visibleIssues(user);
    LocalDateTime month = today().withDayOfMonth(1).atStartOfDay();
    Map<String, Long> status = all
      .stream()
      .collect(Collectors.groupingBy(this::safeStatus, Collectors.counting()));
    long completed = status.getOrDefault("已完成", 0L);
    long reopened = all.stream().filter(i -> Boolean.TRUE.equals(i.getReopened())).count();
    long overdue = all.stream().filter(this::isOverdue).count();
    LocalDateTime dataUpdatedAt = all
      .stream()
      .map(Issue::getUpdatedAt)
      .filter(Objects::nonNull)
      .max(LocalDateTime::compareTo)
      .orElse(now());
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("total", (long) all.size());
    r.put("pending", status.getOrDefault("待处理", 0L));
    r.put("processing", status.getOrDefault("处理中", 0L));
    r.put("verifying", status.getOrDefault("待验证", 0L));
    r.put("completed", completed);
    r.put("reopened", reopened);
    r.put("overdue", overdue);
    r.put("updatedAt", nowWithOffset());
    r.put("dataUpdatedAt", withBusinessOffset(dataUpdatedAt));
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
    return dashboardTrend(null, range);
  }

  public List<Map<String, Object>> dashboardTrend(AuthUser user, String range) {
    List<Issue> all = visibleIssues(user);
    return buildTrend(all, range);
  }

  public Map<String, Object> dashboardAiInsight() {
    return dashboardAiInsight(null);
  }

  public Map<String, Object> dashboardAiInsight(AuthUser user) {
    List<Issue> all = visibleIssues(user);
    LocalDateTime recentStart = today().minusDays(30).atStartOfDay();
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
    result.put("updatedAt", nowWithOffset());
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
    return dashboardAiQuery(null, question);
  }

  public Map<String, Object> dashboardAiQuery(AuthUser user, String question) {
    Map<String, Object> insight = dashboardAiInsight(user);
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
      nowWithOffset()
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

    LocalDate trendAnchor = today();
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
    return report(null);
  }

  public Map<String, Object> report(AuthUser user) {
    List<Issue> all = visibleIssues(user);
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
    List<Issue> overdueIssues = all.stream().filter(this::isOverdue).limit(10).toList();
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

  public Map<String, Object> reportAnalysis(AuthUser user) {
    return reportAnalysis(user, null, null);
  }

  public Map<String, Object> reportAnalysis(
    AuthUser user,
    String startDate,
    String endDate
  ) {
    return reportAnalysis(user, startDate, endDate, null);
  }

  public Map<String, Object> reportAnalysis(
    AuthUser user,
    String startDate,
    String endDate,
    String departments
  ) {
    List<Issue> visible = visibleIssues(user);
    List<String> selectedDepartments = parseFilterValues(departments);
    List<String> availableDepartments = availableDepartments(visible);
    List<Issue> all = filterByDepartments(visible, selectedDepartments);
    ReportPeriod period = resolveReportPeriod(startDate, endDate);
    List<Issue> currentScope = all
      .stream()
      .filter(issue -> activeDuring(issue, period.start(), period.end()))
      .toList();
    List<Issue> previousScope = all
      .stream()
      .filter(issue ->
        activeDuring(issue, period.previousStart(), period.previousEnd())
      )
      .toList();
    AnalysisMetrics current = analysisMetrics(currentScope, period);
    AnalysisMetrics previous = analysisMetrics(
      previousScope,
      new ReportPeriod(
        period.previousStart(),
        period.previousEnd(),
        period.previousStart(),
        period.previousEnd()
      )
    );
    List<Issue> rankedIssues = all
      .stream()
      .filter(issue -> activeDuring(issue, period.start(), period.end()))
      .sorted(
        Comparator
          .comparingInt(this::issueRiskScore)
          .reversed()
          .thenComparing(
            issue -> Optional.ofNullable(issue.getCreatedAt()).orElse(LocalDateTime.MIN),
            Comparator.reverseOrder()
          )
      )
      .limit(200)
      .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
      "period",
      map(
        "startDate",
        period.start().toString(),
        "endDate",
        period.end().toString(),
        "previousStartDate",
        period.previousStart().toString(),
        "previousEndDate",
        period.previousEnd().toString(),
        "label",
        period.start() + " 至 " + period.end(),
        "previousLabel",
        period.previousStart() + " 至 " + period.previousEnd()
      )
    );
    result.put(
      "appliedFilters",
      map(
        "departments",
        selectedDepartments,
        "startDate",
        startDate,
        "endDate",
        endDate
      )
    );
    result.put("availableDepartments", availableDepartments);
    result.put("summary", buildAnalysisSummary(current, previous));
    result.put("periodSummary", buildPeriodSummary(current, previous));
    result.put(
      "dimensions",
      List.of(
        buildDimension(
          "businessScene",
          "业务场景",
          currentScope,
          period,
          Issue::getBusinessScene
        ),
        buildDimension(
          "issueType",
          "问题类型",
          currentScope,
          period,
          Issue::getIssueType
        ),
        buildDimension(
          "responsibleDepartment",
          "责任部门",
          currentScope,
          period,
          Issue::getResponsibleDepartment
        ),
        buildDimension("source", "问题来源", currentScope, period, Issue::getSource),
        buildDimension(
          "impactScope",
          "影响范围",
          currentScope,
          period,
          Issue::getImpactScope
        )
      )
    );
    List<Map<String, Object>> trend = buildAnalysisTrend(all, period);
    result.put("trend", trend);
    result.put("efficiencyBuckets", buildEfficiencyBuckets(currentScope, period));
    result.put("keyChanges", buildKeyChanges(current, previous));
    result.put("structureMatrix", buildStructureMatrix(currentScope, period));
    result.put("priorityEfficiency", buildPriorityEfficiency(currentScope, period));
    result.put(
      "datasets",
      buildAnalysisDatasets(current, result.get("dimensions"))
    );
    result.put("events", buildTrendEvents(trend));
    result.put("issues", rankedIssues);
    result.put(
      "metricDefinitions",
      List.of(
        "超期率 = 已超过预计完成时间且未实际完成的问题 / 当前可见问题数",
        "复发率 = 标记为复发的问题 / 当前可见问题数",
        "SLA 达成率 = 1 - 超期问题数 / 当前可见问题数",
        "平均处理时长 = 已完成问题从创建到实际完成的平均耗时"
      )
    );
    result.put("updatedAt", nowWithOffset());
    return result;
  }

  private Map<String, Object> buildAnalysisSummary(
    AnalysisMetrics current,
    AnalysisMetrics previous
  ) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total", current.total());
    summary.put("current", current.total());
    summary.put("newIssues", current.newIssues());
    summary.put("pending", current.pending());
    summary.put("overdue", current.overdue());
    summary.put("reopened", current.reopened());
    summary.put("highPriority", current.highPriority());
    summary.put("completed", current.completed());
    summary.put("slaRate", round1(current.slaRate()));
    summary.put("overdueRate", round1(current.overdueRate()));
    summary.put("reopenedRate", round1(current.reopenedRate()));
    summary.put("averageHandleHours", round1(current.averageHandleHours()));
    summary.put("governanceScore", current.governanceScore());
    summary.put("governanceDelta", current.governanceScore() - previous.governanceScore());
    summary.put(
      "subScores",
      List.of(
        subScore("闭环效率", current.closureScore(), previous.closureScore()),
        subScore("超期控制", current.overdueScore(), previous.overdueScore()),
        subScore("复发控制", current.reopenedScore(), previous.reopenedScore()),
        subScore("高优先级响应", current.responseScore(), previous.responseScore())
      )
    );
    return summary;
  }

  private Map<String, Object> subScore(String label, int current, int previous) {
    int delta = current - previous;
    return map(
      "label",
      label,
      "value",
      current,
      "delta",
      signed(delta),
      "deltaValue",
      delta,
      "deltaTone",
      delta >= 0 ? "up" : "down"
    );
  }

  private List<Map<String, Object>> buildPeriodSummary(
    AnalysisMetrics current,
    AnalysisMetrics previous
  ) {
    return List.of(
      periodMetric("新增问题", current.newIssues(), previous.newIssues(), false),
      periodMetric("完成问题", current.completed(), previous.completed(), false),
      periodMetric("待处理问题", current.pending(), previous.pending(), true),
      periodMetric("超期问题", current.overdue(), previous.overdue(), true)
    );
  }

  private Map<String, Object> periodMetric(
    String label,
    long current,
    long previous,
    boolean lowerIsBetter
  ) {
    long delta = current - previous;
    String tone = delta == 0
      ? "flat"
      : lowerIsBetter
        ? (delta > 0 ? "danger" : "up")
        : (delta > 0 ? "up" : "down");
    return map(
      "label",
      label,
      "value",
      current,
      "previousValue",
      previous,
      "delta",
      signed(delta),
      "deltaValue",
      delta,
      "deltaRate",
      round1(percentChange(current, previous)),
      "tone",
      tone
    );
  }

  private Map<String, Object> buildDimension(
    String key,
    String label,
    List<Issue> all,
    ReportPeriod period,
    Function<Issue, String> extractor
  ) {
    long total = all.size();
    List<Map<String, Object>> items = all
      .stream()
      .collect(Collectors.groupingBy(issue -> value(extractor.apply(issue), "未分配")))
      .entrySet()
      .stream()
      .map(entry -> buildDimensionItem(entry.getKey(), entry.getValue(), total, period))
      .sorted((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value")))
      .limit(12)
      .toList();
    return map("key", key, "label", label, "items", items);
  }

  private Map<String, Object> buildDimensionItem(
    String name,
    List<Issue> group,
    long total,
    ReportPeriod period
  ) {
    long count = group.size();
    long overdue = group.stream().filter(issue -> isOverdueAtEnd(issue, period.end())).count();
    long reopened = group.stream().filter(i -> Boolean.TRUE.equals(i.getReopened())).count();
    long highPriority = group
      .stream()
      .filter(issue -> isHighPriority(issue) && !isCompletedByEnd(issue, period.end()))
      .count();
    return map(
      "key",
      name,
      "name",
      name,
      "value",
      count,
      "share",
      round1(ratio(count, total)),
      "overdueCount",
      overdue,
      "overdueRate",
      round1(ratio(overdue, count)),
      "reopenedCount",
      reopened,
      "reopenedRate",
      round1(ratio(reopened, count)),
      "averageHandleHours",
      round1(averageHandleHoursInPeriod(group, period)),
      "riskLevel",
      riskLevel(overdue, reopened, highPriority)
    );
  }

  private List<Map<String, Object>> buildAnalysisTrend(
    List<Issue> all,
    ReportPeriod period
  ) {
    List<Map<String, Object>> trend = new ArrayList<>();
    for (
      LocalDate date = period.start();
      !date.isAfter(period.end());
      date = date.plusDays(1)
    ) {
      LocalDate currentDate = date;
      long created = all
        .stream()
        .filter(issue -> createdOn(issue, currentDate))
        .count();
      long completed = all
        .stream()
        .filter(issue -> completedOn(issue, currentDate))
        .count();
      long pending = all.stream().filter(issue -> existedAndOpenOn(issue, currentDate)).count();
      long overdue = all.stream().filter(issue -> overdueOn(issue, currentDate)).count();
      trend.add(
        map(
          "date",
          currentDate.toString(),
          "newIssues",
          created,
          "completed",
          completed,
          "pending",
          pending,
          "overdue",
          overdue
        )
      );
    }
    return trend;
  }

  private List<Map<String, Object>> buildEfficiencyBuckets(
    List<Issue> all,
    ReportPeriod period
  ) {
    List<Issue> completed = all
      .stream()
      .filter(issue -> completedInPeriod(issue, period))
      .filter(issue -> issue.getCreatedAt() != null)
      .toList();
    return List.of(
      buildEfficiencyBucket("0-1天", completed, 0, 24),
      buildEfficiencyBucket("1-3天", completed, 24, 72),
      buildEfficiencyBucket("3-7天", completed, 72, 168),
      buildEfficiencyBucket("7天以上", completed, 168, Long.MAX_VALUE)
    );
  }

  private Map<String, Object> buildEfficiencyBucket(
    String label,
    List<Issue> completed,
    long minHours,
    long maxHours
  ) {
    List<Issue> bucket = completed
      .stream()
      .filter(issue -> {
        long hours = Duration.between(issue.getCreatedAt(), issue.getActualFinishTime()).toHours();
        return hours >= minHours && hours < maxHours;
      })
      .toList();
    long highPriority = bucket.stream().filter(this::isHighPriority).count();
    return map(
      "label",
      label,
      "total",
      bucket.size(),
      "highPriority",
      highPriority,
      "normal",
      bucket.size() - highPriority
    );
  }

  private List<Map<String, Object>> buildKeyChanges(
    AnalysisMetrics current,
    AnalysisMetrics previous
  ) {
    return List.of(
      keyChange(
        "newIssues",
        trendTitle(current.newIssues(), previous.newIssues(), "新增问题数上升", "新增问题数下降", "新增问题数持平"),
        "新增问题 " + previous.newIssues() + " → " + current.newIssues(),
        percentChange(current.newIssues(), previous.newIssues()),
        current.newIssues(),
        false
      ),
      keyChange(
        "overdue",
        trendTitle(current.overdue(), previous.overdue(), "超期问题数上升", "超期问题数下降", "超期问题数持平"),
        "超期问题 " + previous.overdue() + " → " + current.overdue(),
        percentChange(current.overdue(), previous.overdue()),
        current.overdue(),
        true
      ),
      keyChange(
        "averageHandleDays",
        trendTitle(
          current.averageHandleHours(),
          previous.averageHandleHours(),
          "平均处理时长延长",
          "平均处理时长缩短",
          "平均处理时长持平"
        ),
        "平均处理时长 " +
        round1(previous.averageHandleHours() / 24) +
        "天 → " +
        round1(current.averageHandleHours() / 24) +
        "天",
        round1((current.averageHandleHours() - previous.averageHandleHours()) / 24),
        Math.max(1, current.completed()),
        true,
        "天"
      ),
      keyChange(
        "reopened",
        trendTitle(current.reopened(), previous.reopened(), "复发问题数上升", "复发问题数下降", "复发问题数持平"),
        "复发问题 " + previous.reopened() + " → " + current.reopened(),
        percentChange(current.reopened(), previous.reopened()),
        current.reopened(),
        true
      ),
      keyChange(
        "highPriority",
        current.highPriority() == previous.highPriority()
          ? "高优问题积压持平"
          : current.highPriority() < previous.highPriority()
          ? "高优问题响应改善"
          : "高优问题积压上升",
        "P0/P1 未完成 " + previous.highPriority() + " → " + current.highPriority(),
        percentChange(current.highPriority(), previous.highPriority()),
        current.highPriority(),
        true
      )
    );
  }

  private String trendTitle(
    double current,
    double previous,
    String upTitle,
    String downTitle,
    String flatTitle
  ) {
    if (current > previous) return upTitle;
    if (current < previous) return downTitle;
    return flatTitle;
  }

  private Map<String, Object> keyChange(
    String metric,
    String title,
    String description,
    double delta,
    long evidence,
    boolean lowerIsBetter
  ) {
    return keyChange(metric, title, description, delta, evidence, lowerIsBetter, "%");
  }

  private Map<String, Object> keyChange(
    String metric,
    String title,
    String description,
    double delta,
    long evidence,
    boolean lowerIsBetter,
    String unit
  ) {
    String direction = delta > 0 ? "up" : delta < 0 ? "down" : "flat";
    String tone = delta == 0
      ? "flat"
      : lowerIsBetter
      ? (delta > 0 ? "up danger" : "down")
      : (delta >= 0 ? "up" : "down");
    String value = "%".equals(unit)
      ? signed(round1(delta)) + "%"
      : signed(round1(delta)) + " " + unit;
    return map(
      "metric",
      metric,
      "title",
      title,
      "description",
      description,
      "detail",
      description,
      "value",
      value,
      "delta",
      round1(delta),
      "direction",
      direction,
      "tone",
      tone,
      "evidence",
      evidence
    );
  }

  private List<Map<String, Object>> buildStructureMatrix(
    List<Issue> currentScope,
    ReportPeriod period
  ) {
    return currentScope
      .stream()
      .collect(Collectors.groupingBy(issue -> value(issue.getIssueType(), "未分配")))
      .entrySet()
      .stream()
      .map(entry -> {
        List<Issue> group = entry.getValue();
        long total = group.size();
        long reopened = group.stream().filter(i -> Boolean.TRUE.equals(i.getReopened())).count();
        long overdue = group
          .stream()
          .filter(issue -> isOverdueAtEnd(issue, period.end()))
          .count();
        return map(
          "name",
          entry.getKey(),
          "source",
          Math.round(topShare(group, Issue::getSource)),
          "impact",
          Math.round(topShare(group, Issue::getImpactScope)),
          "reopened",
          Math.round(ratio(reopened, total)),
          "overdue",
          Math.round(ratio(overdue, total)),
          "value",
          total
        );
      })
      .sorted((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value")))
      .limit(6)
      .toList();
  }

  private List<Map<String, Object>> buildPriorityEfficiency(
    List<Issue> currentScope,
    ReportPeriod period
  ) {
    List<Issue> completed = currentScope
      .stream()
      .filter(issue -> completedInPeriod(issue, period))
      .filter(issue -> issue.getCreatedAt() != null)
      .toList();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String priority : List.of("P0", "P1", "P2", "P3")) {
      List<Issue> group = completed
        .stream()
        .filter(issue -> priority.equals(value(issue.getPriority(), "P3")))
        .toList();
      rows.add(priorityEfficiencyRow(priority, group));
    }
    rows.add(priorityEfficiencyRow("整体", completed));
    return rows;
  }

  private Map<String, Object> priorityEfficiencyRow(String label, List<Issue> issues) {
    long[] counts = new long[] { 0, 0, 0, 0 };
    for (Issue issue : issues) {
      long hours = Duration.between(issue.getCreatedAt(), completedAt(issue)).toHours();
      if (hours <= 24) counts[0]++;
      else if (hours <= 72) counts[1]++;
      else if (hours <= 168) counts[2]++;
      else counts[3]++;
    }
    long total = Arrays.stream(counts).sum();
    List<Integer> values = Arrays
      .stream(counts)
      .mapToInt(count -> total == 0 ? 0 : (int) Math.round((count * 100.0) / total))
      .boxed()
      .toList();
    double averageDays = issues
      .stream()
      .mapToDouble(issue ->
        Duration.between(issue.getCreatedAt(), completedAt(issue)).toHours() / 24.0
      )
      .average()
      .orElse(0);
    return map(
      "label",
      label,
      "values",
      values,
      "average",
      round1(averageDays),
      "averageDays",
      round1(averageDays),
      "total",
      total
    );
  }

  private List<Map<String, Object>> buildAnalysisDatasets(
    AnalysisMetrics current,
    Object dimensionsValue
  ) {
    List<?> dimensions = dimensionsValue instanceof List<?> list ? list : List.of();
    long departmentCount = dimensionItemCount(dimensions, "responsibleDepartment");
    long typeCount = dimensionItemCount(dimensions, "issueType");
    return List.of(
      dataset("issueDetail", "问题明细", "按问题维度的完整明细数据", current.total(), "条", "primary"),
      dataset("departmentRanking", "部门排行", "部门多维度排行与对比", departmentCount, "条", "green"),
      dataset("typeDetail", "类型明细", "问题类型多维度分析", typeCount, "类", "primary"),
      dataset("overdueList", "超期清单", "超期问题清单与明细", current.overdue(), "条", "danger"),
      dataset("reopenedList", "复发清单", "复发问题清单与明细", current.reopened(), "条", "green")
    );
  }

  private Map<String, Object> dataset(
    String key,
    String title,
    String description,
    long count,
    String unit,
    String tone
  ) {
    return map(
      "key",
      key,
      "title",
      title,
      "desc",
      description,
      "description",
      description,
      "count",
      count,
      "unit",
      unit,
      "countLabel",
      count + " " + unit,
      "tone",
      tone
    );
  }

  private long dimensionItemCount(List<?> dimensions, String key) {
    for (Object dimension : dimensions) {
      if (!(dimension instanceof Map<?, ?> map)) continue;
      if (!key.equals(String.valueOf(map.get("key")))) continue;
      Object items = map.get("items");
      return items instanceof List<?> list ? list.size() : 0;
    }
    return 0;
  }

  private List<Map<String, Object>> buildTrendEvents(List<Map<String, Object>> trend) {
    if (trend.isEmpty()) return List.of();
    List<Map<String, Object>> events = new ArrayList<>();
    addTrendEvent(events, maxTrendDate(trend, "newIssues"), "新增问题峰值");
    addTrendEvent(events, maxTrendDate(trend, "completed"), "集中完成处理");
    addTrendEvent(events, maxTrendDate(trend, "overdue"), "超期风险抬升");
    return events
      .stream()
      .collect(
        Collectors.toMap(
          item -> String.valueOf(item.get("date")) + item.get("label"),
          Function.identity(),
          (a, b) -> a,
          LinkedHashMap::new
        )
      )
      .values()
      .stream()
      .toList();
  }

  private void addTrendEvent(
    List<Map<String, Object>> events,
    String date,
    String label
  ) {
    if (date != null) events.add(map("date", date, "label", label));
  }

  private String maxTrendDate(List<Map<String, Object>> trend, String key) {
    return trend
      .stream()
      .max(Comparator.comparingLong(item -> longValue(item.get(key))))
      .filter(item -> longValue(item.get(key)) > 0)
      .map(item -> String.valueOf(item.get("date")))
      .orElse(null);
  }

  private int issueRiskScore(Issue issue) {
    int score = 0;
    if (isOverdue(issue)) score += 100;
    if (Boolean.TRUE.equals(issue.getReopened())) score += 80;
    if ("P0".equals(issue.getPriority())) score += 70;
    else if ("P1".equals(issue.getPriority())) score += 50;
    if (!isCompleted(issue)) score += 20;
    return score;
  }

  private boolean isHighPriorityOpen(Issue issue) {
    return isHighPriority(issue) && !isCompleted(issue);
  }

  private boolean isHighPriority(Issue issue) {
    return "P0".equals(issue.getPriority()) || "P1".equals(issue.getPriority());
  }

  private boolean isCompleted(Issue issue) {
    return "已完成".equals(issue.getStatus());
  }

  private boolean sameDate(LocalDateTime value, LocalDate date) {
    return value != null && value.toLocalDate().equals(date);
  }

  private ReportPeriod resolveReportPeriod(String startDate, String endDate) {
    LocalDate defaultMonth = today().minusMonths(1);
    LocalDate defaultStart = defaultMonth.withDayOfMonth(1);
    LocalDate defaultEnd = defaultMonth.withDayOfMonth(defaultMonth.lengthOfMonth());
    LocalDate end = parseDate(endDate).orElse(defaultEnd);
    LocalDate start = parseDate(startDate).orElse(defaultStart);
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("开始日期不能晚于结束日期");
    }
    long days = ChronoUnit.DAYS.between(start, end) + 1;
    LocalDate previousEnd = start.minusDays(1);
    LocalDate previousStart = previousEnd.minusDays(days - 1);
    return new ReportPeriod(start, end, previousStart, previousEnd);
  }

  private Optional<LocalDate> parseDate(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    return Optional.of(LocalDate.parse(value.trim()));
  }

  private AnalysisMetrics analysisMetrics(
    List<Issue> scope,
    ReportPeriod period
  ) {
    long total = scope.size();
    long newIssues = scope.stream().filter(issue -> createdInPeriod(issue, period)).count();
    long completed = scope.stream().filter(issue -> completedInPeriod(issue, period)).count();
    long pending = scope.stream().filter(issue -> openAtEnd(issue, period.end())).count();
    long overdue = scope.stream().filter(issue -> isOverdueAtEnd(issue, period.end())).count();
    long reopened = scope.stream().filter(i -> Boolean.TRUE.equals(i.getReopened())).count();
    long highPriority = scope
      .stream()
      .filter(issue -> isHighPriority(issue) && !isCompletedByEnd(issue, period.end()))
      .count();
    double averageHours = averageHandleHoursInPeriod(scope, period);
    double overdueRate = ratio(overdue, total);
    double reopenedRate = ratio(reopened, total);
    double slaRate = total == 0 ? 100 : Math.max(0, 100 - overdueRate);
    int closureScore = total == 0 ? 100 : score(ratio(completed, total));
    int overdueScore = score(100 - overdueRate);
    int reopenedScore = score(100 - reopenedRate);
    int responseScore = total == 0 ? 100 : score(100 - ratio(highPriority, total));
    int governanceScore = Math.round(
      (closureScore * 0.28f) +
      (overdueScore * 0.32f) +
      (reopenedScore * 0.24f) +
      (responseScore * 0.16f)
    );
    return new AnalysisMetrics(
      total,
      newIssues,
      completed,
      pending,
      overdue,
      reopened,
      highPriority,
      slaRate,
      overdueRate,
      reopenedRate,
      averageHours,
      governanceScore,
      closureScore,
      overdueScore,
      reopenedScore,
      responseScore
    );
  }

  private boolean existedAndOpenOn(Issue issue, LocalDate date) {
    if (issue.getCreatedAt() == null || issue.getCreatedAt().toLocalDate().isAfter(date)) return false;
    LocalDateTime completedAt = completedAt(issue);
    return completedAt == null || completedAt.toLocalDate().isAfter(date);
  }

  private boolean overdueOn(Issue issue, LocalDate date) {
    if (issue.getExpectedFinishTime() == null || !issue.getExpectedFinishTime().toLocalDate().isBefore(date)) return false;
    LocalDateTime completedAt = completedAt(issue);
    return completedAt == null || completedAt.toLocalDate().isAfter(date);
  }

  private boolean activeDuring(Issue issue, LocalDate start, LocalDate end) {
    LocalDate created = issue.getCreatedAt() == null
      ? LocalDate.MIN
      : issue.getCreatedAt().toLocalDate();
    if (created.isAfter(end)) return false;
    LocalDateTime completedAt = completedAt(issue);
    return completedAt == null || !completedAt.toLocalDate().isBefore(start);
  }

  private boolean createdInPeriod(Issue issue, ReportPeriod period) {
    LocalDate created = issue.getCreatedAt() == null
      ? null
      : issue.getCreatedAt().toLocalDate();
    return created != null && !created.isBefore(period.start()) && !created.isAfter(period.end());
  }

  private boolean completedInPeriod(Issue issue, ReportPeriod period) {
    LocalDateTime completedAt = completedAt(issue);
    if (completedAt == null) return false;
    LocalDate completed = completedAt.toLocalDate();
    return !completed.isBefore(period.start()) && !completed.isAfter(period.end());
  }

  private boolean createdOn(Issue issue, LocalDate date) {
    return sameDate(issue.getCreatedAt(), date);
  }

  private boolean completedOn(Issue issue, LocalDate date) {
    LocalDateTime completedAt = completedAt(issue);
    return completedAt != null && completedAt.toLocalDate().equals(date);
  }

  private boolean openAtEnd(Issue issue, LocalDate end) {
    if (issue.getCreatedAt() != null && issue.getCreatedAt().toLocalDate().isAfter(end)) return false;
    return !isCompletedByEnd(issue, end);
  }

  private boolean isCompletedByEnd(Issue issue, LocalDate end) {
    LocalDateTime completedAt = completedAt(issue);
    return completedAt != null && !completedAt.toLocalDate().isAfter(end);
  }

  private LocalDateTime completedAt(Issue issue) {
    if (issue.getActualFinishTime() != null) return issue.getActualFinishTime();
    if (isCompleted(issue) && issue.getUpdatedAt() != null) return issue.getUpdatedAt();
    return null;
  }

  private boolean isOverdueAtEnd(Issue issue, LocalDate end) {
    if (issue.getExpectedFinishTime() == null) return false;
    return (
      !issue.getExpectedFinishTime().toLocalDate().isAfter(end) &&
      !isCompletedByEnd(issue, end)
    );
  }

  private double averageHandleHours(List<Issue> issueList) {
    return issueList
      .stream()
      .filter(i -> i.getActualFinishTime() != null && i.getCreatedAt() != null)
      .mapToLong(i -> Duration.between(i.getCreatedAt(), i.getActualFinishTime()).toHours())
      .average()
      .orElse(0);
  }

  private double averageHandleHoursInPeriod(
    List<Issue> issueList,
    ReportPeriod period
  ) {
    return issueList
      .stream()
      .filter(issue -> issue.getCreatedAt() != null && completedInPeriod(issue, period))
      .mapToLong(issue -> Duration.between(issue.getCreatedAt(), completedAt(issue)).toHours())
      .average()
      .orElse(0);
  }

  private double topShare(List<Issue> issues, Function<Issue, String> extractor) {
    long total = issues.size();
    if (total == 0) return 0;
    long top = issues
      .stream()
      .collect(Collectors.groupingBy(issue -> value(extractor.apply(issue), "未分配"), Collectors.counting()))
      .values()
      .stream()
      .mapToLong(Long::longValue)
      .max()
      .orElse(0);
    return ratio(top, total);
  }

  private String topGroupName(
    List<Issue> all,
    Function<Issue, String> extractor,
    String fallback
  ) {
    return all
      .stream()
      .collect(Collectors.groupingBy(issue -> value(extractor.apply(issue), fallback), Collectors.counting()))
      .entrySet()
      .stream()
      .max(Map.Entry.comparingByValue())
      .map(Map.Entry::getKey)
      .orElse(fallback);
  }

  private String riskLevel(long overdue, long reopened, long highPriority) {
    if (overdue > 0 || highPriority > 1) return "高";
    if (reopened > 0 || highPriority > 0) return "中";
    return "低";
  }

  private int score(double value) {
    return (int) Math.max(0, Math.min(100, Math.round(value)));
  }

  private double ratio(long value, long total) {
    return total == 0 ? 0 : (value * 100.0) / total;
  }

  private double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }

  private double percentChange(double current, double previous) {
    if (previous == 0) return current == 0 ? 0 : 100;
    return ((current - previous) / previous) * 100.0;
  }

  private String signed(long value) {
    return value > 0 ? "+" + value : String.valueOf(value);
  }

  private String signed(double value) {
    double rounded = round1(value);
    return rounded > 0 ? "+" + rounded : String.valueOf(rounded);
  }

  private long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    if (value == null) return 0;
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private String deltaText(long value, String label) {
    return value + " 个" + label;
  }

  private String value(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private List<String> parseFilterValues(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays
      .stream(value.split(","))
      .map(String::trim)
      .filter(item -> !item.isBlank())
      .distinct()
      .toList();
  }

  private List<String> availableDepartments(List<Issue> all) {
    return all
      .stream()
      .map(issue -> value(issue.getResponsibleDepartment(), "未分配"))
      .distinct()
      .sorted()
      .toList();
  }

  private List<Issue> filterByDepartments(
    List<Issue> all,
    List<String> departments
  ) {
    if (departments == null || departments.isEmpty()) return all;
    Set<String> selected = new LinkedHashSet<>(departments);
    return all
      .stream()
      .filter(issue ->
        selected.contains(value(issue.getResponsibleDepartment(), "未分配"))
      )
      .toList();
  }

  private Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length - 1; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
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

  private boolean isActive(Issue issue) {
    return !Boolean.TRUE.equals(issue.getDeleted());
  }

  private List<Issue> visibleIssues(AuthUser user) {
    List<Issue> all = issues.findAll().stream().filter(this::isActive).toList();
    return user == null ? all : dataScopeService.filterVisible(user, all);
  }

  private Predicate activePredicate(
    jakarta.persistence.criteria.Root<Issue> root,
    jakarta.persistence.criteria.CriteriaBuilder cb
  ) {
    return cb.or(cb.isNull(root.get("deleted")), cb.isFalse(root.get("deleted")));
  }

  private String safeStatus(Issue issue) {
    String status = issue.getStatus();
    return status == null || status.isBlank() ? "待处理" : status;
  }

  private boolean isOverdue(Issue issue) {
    return (
      issue.getExpectedFinishTime() != null &&
      issue.getExpectedFinishTime().isBefore(now()) &&
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
      "PBI-" + today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
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
