package com.company.issueops.service;

import com.company.issueops.domain.Issue;
import com.company.issueops.service.AuthService.AuthUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class DataScopeService {

  public static final String ALL = "ALL";
  public static final String DEPARTMENT = "DEPARTMENT";
  public static final String OWN = "OWN";
  public static final String ASSIGNED = "ASSIGNED";

  private static final List<String> VALID_SCOPES = List.of(
    ALL,
    DEPARTMENT,
    OWN,
    ASSIGNED
  );

  public String normalizeScope(String scope, String role) {
    String normalized = Objects
      .toString(scope, "")
      .trim()
      .toUpperCase(Locale.ROOT);
    return VALID_SCOPES.contains(normalized) ? normalized : defaultScope(role);
  }

  public String defaultScope(String role) {
    return switch (Objects.toString(role, "").trim().toUpperCase(Locale.ROOT)) {
      case "ADMIN", "PRODUCT" -> ALL;
      case "TECH" -> DEPARTMENT;
      case "CS" -> OWN;
      default -> DEPARTMENT;
    };
  }

  public String defaultDepartment(String role, String displayName) {
    return switch (Objects.toString(role, "").trim().toUpperCase(Locale.ROOT)) {
      case "ADMIN" -> "公司全局";
      case "PRODUCT" -> "产品部";
      case "TECH" -> "技术部";
      case "CS" -> "客服部";
      default -> Objects.toString(displayName, "未分配").trim();
    };
  }

  public Predicate visibleIssuePredicate(
    AuthUser user,
    Root<Issue> root,
    CriteriaBuilder cb
  ) {
    if (user == null || ALL.equals(user.dataScope())) return cb.conjunction();

    Predicate createdByUser = cb.or(
      cb.equal(root.get("createdBy"), user.displayName()),
      cb.equal(root.get("createdBy"), user.username())
    );
    Predicate assignedToUser = cb.or(
      cb.equal(root.get("responsiblePerson"), user.displayName()),
      cb.equal(root.get("responsiblePerson"), user.username())
    );
    Predicate sameDepartment = isBlank(user.department())
      ? cb.disjunction()
      : cb.equal(root.get("responsibleDepartment"), user.department());

    return switch (user.dataScope()) {
      case DEPARTMENT -> cb.or(sameDepartment, createdByUser, assignedToUser);
      case OWN -> createdByUser;
      case ASSIGNED -> assignedToUser;
      default -> cb.conjunction();
    };
  }

  public boolean canSee(AuthUser user, Issue issue) {
    if (issue == null || Boolean.TRUE.equals(issue.getDeleted())) return false;
    if (user == null || ALL.equals(user.dataScope())) return true;

    boolean createdByUser =
      same(issue.getCreatedBy(), user.displayName()) ||
      same(issue.getCreatedBy(), user.username());
    boolean assignedToUser =
      same(issue.getResponsiblePerson(), user.displayName()) ||
      same(issue.getResponsiblePerson(), user.username());
    boolean sameDepartment = !isBlank(user.department()) &&
    same(issue.getResponsibleDepartment(), user.department());

    return switch (user.dataScope()) {
      case DEPARTMENT -> sameDepartment || createdByUser || assignedToUser;
      case OWN -> createdByUser;
      case ASSIGNED -> assignedToUser;
      default -> true;
    };
  }

  public List<Issue> filterVisible(AuthUser user, Collection<Issue> issues) {
    if (issues == null) return List.of();
    return issues.stream().filter(issue -> canSee(user, issue)).toList();
  }

  public String scopeKey(AuthUser user) {
    if (user == null) return "system:ALL";
    return String.join(
      ":",
      user.username(),
      user.role(),
      Objects.toString(user.department(), ""),
      Objects.toString(user.dataScope(), ALL)
    );
  }

  private boolean same(String left, String right) {
    return (
      !isBlank(left) &&
      !isBlank(right) &&
      left.trim().equalsIgnoreCase(right.trim())
    );
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
