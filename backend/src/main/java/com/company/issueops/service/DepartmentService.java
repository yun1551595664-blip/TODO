package com.company.issueops.service;

import com.company.issueops.domain.DepartmentConfig;
import com.company.issueops.domain.RoleConfig;
import com.company.issueops.domain.UserAccount;
import com.company.issueops.repository.DepartmentConfigRepository;
import com.company.issueops.repository.RoleConfigRepository;
import com.company.issueops.repository.UserAccountRepository;
import com.company.issueops.web.DepartmentRequest;
import com.company.issueops.web.DepartmentSyncRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DepartmentService {

  private final DepartmentConfigRepository departments;
  private final UserAccountRepository accounts;
  private final RoleConfigRepository roles;

  @Value("${org.departments:全部,产品部,技术部,客服部,管理部}")
  private String configuredDepartments;

  @Transactional
  public void syncBaselineDepartments() {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    parseConfiguredDepartments().forEach(names::add);
    accounts
      .findAll()
      .stream()
      .map(UserAccount::getDepartment)
      .map(this::normalizeName)
      .filter(Objects::nonNull)
      .forEach(names::add);
    roles
      .findAll()
      .stream()
      .map(RoleConfig::getDefaultDepartment)
      .map(this::normalizeName)
      .filter(Objects::nonNull)
      .forEach(names::add);

    int sort = 10;
    for (String name : names) {
      ensureDepartment(name, "SYSTEM", sort);
      sort += 10;
    }
  }

  public List<DepartmentView> listDepartments(boolean enabledOnly) {
    return (enabledOnly
        ? departments.findByEnabledTrueOrderBySortOrderAscNameAsc()
        : departments.findAllByOrderBySortOrderAscNameAsc())
      .stream()
      .map(this::toView)
      .toList();
  }

  @Transactional
  public DepartmentView create(DepartmentRequest request) {
    DepartmentConfig department = new DepartmentConfig();
    applyRequest(department, request, true);
    department.setSource("MANUAL");
    return toView(departments.save(department));
  }

  @Transactional
  public DepartmentView update(Long id, DepartmentRequest request) {
    DepartmentConfig department = findRequired(id);
    applyRequest(department, request, false);
    return toView(departments.save(department));
  }

  @Transactional
  public DepartmentView setEnabled(Long id, boolean enabled) {
    DepartmentConfig department = findRequired(id);
    if (!enabled && usageCount(department) > 0) {
      throw new IllegalArgumentException("Department is in use and cannot be disabled");
    }
    department.setEnabled(enabled);
    return toView(departments.save(department));
  }

  @Transactional
  public void delete(Long id) {
    DepartmentConfig department = findRequired(id);
    if (usageCount(department) > 0) {
      throw new IllegalArgumentException("Department is in use and cannot be deleted");
    }
    departments.delete(department);
  }

  @Transactional
  public List<DepartmentView> sync(DepartmentSyncRequest request) {
    if (request == null || request.departments() == null || request.departments().isEmpty()) {
      throw new IllegalArgumentException("部门同步数据不能为空");
    }
    int index = 1;
    for (DepartmentSyncRequest.DepartmentEntry entry : request.departments()) {
      String name = normalizeName(entry.name());
      if (name == null) throw new IllegalArgumentException("部门名称不能为空");
      DepartmentConfig department = departments
        .findByName(name)
        .orElseGet(DepartmentConfig::new);
      department.setCode(normalizeCode(entry.code(), name));
      department.setName(name);
      department.setParentCode(trimToNull(entry.parentCode()));
      department.setEnabled(entry.enabled() == null || entry.enabled());
      department.setSortOrder(entry.sortOrder() == null ? index * 10 : entry.sortOrder());
      department.setSource("SYNC");
      departments.save(department);
      index++;
    }
    return listDepartments(false);
  }

  @Transactional
  public String ensureDepartment(String departmentName, String source) {
    String name = normalizeName(departmentName);
    if (name == null) return null;
    return ensureDepartment(name, source, 100).getName();
  }

  public String normalizeDepartment(String departmentName, String fallback) {
    String name = normalizeName(departmentName);
    if (name == null) name = normalizeName(fallback);
    if (name == null) return "未分配";
    return departments.findByName(name).map(DepartmentConfig::getName).orElse(name);
  }

  private DepartmentConfig ensureDepartment(String name, String source, int sortOrder) {
    DepartmentConfig department = departments.findByName(name).orElseGet(DepartmentConfig::new);
    if (department.getCode() == null || department.getCode().isBlank()) {
      department.setCode(normalizeCode(null, name));
    }
    department.setName(name);
    if (department.getSortOrder() == null) department.setSortOrder(sortOrder);
    if (department.getEnabled() == null) department.setEnabled(true);
    department.setSource(nonBlank(source, "SYSTEM"));
    return departments.save(department);
  }

  private List<String> parseConfiguredDepartments() {
    return Arrays
      .stream(Objects.toString(configuredDepartments, "").split("[,;]"))
      .map(this::normalizeName)
      .filter(Objects::nonNull)
      .distinct()
      .toList();
  }

  private DepartmentView toView(DepartmentConfig department) {
    long accountCount = accounts.countByDepartment(department.getName());
    long roleCount = roles.countByDefaultDepartment(department.getName());
    return new DepartmentView(
      department.getId(),
      department.getCode(),
      department.getName(),
      department.getParentCode(),
      Boolean.TRUE.equals(department.getEnabled()),
      department.getSortOrder(),
      department.getSource(),
      accountCount,
      roleCount,
      department.getCreatedAt(),
      department.getUpdatedAt()
    );
  }

  private DepartmentConfig findRequired(Long id) {
    return departments
      .findById(id)
      .orElseThrow(() -> new IllegalArgumentException("Department not found"));
  }

  private void applyRequest(
    DepartmentConfig department,
    DepartmentRequest request,
    boolean creating
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Department request is required");
    }
    String name = normalizeName(request.name());
    if (name == null) {
      throw new IllegalArgumentException("Department name is required");
    }
    String code = creating
      ? normalizeCode(request.code(), name)
      : normalizeCode(department.getCode(), name);
    Long id = department.getId();
    departments
      .findByCode(code)
      .filter(existing -> !Objects.equals(existing.getId(), id))
      .ifPresent(existing -> {
        throw new IllegalArgumentException("Department code already exists");
      });
    departments
      .findByName(name)
      .filter(existing -> !Objects.equals(existing.getId(), id))
      .ifPresent(existing -> {
        throw new IllegalArgumentException("Department name already exists");
      });

    if (!creating && !Objects.equals(department.getName(), name) && usageCount(department) > 0) {
      throw new IllegalArgumentException("Department is in use and cannot be renamed");
    }
    if (Boolean.FALSE.equals(request.enabled()) && usageCount(department) > 0) {
      throw new IllegalArgumentException("Department is in use and cannot be disabled");
    }

    department.setCode(code);
    department.setName(name);
    department.setParentCode(trimToNull(request.parentCode()));
    department.setEnabled(request.enabled() == null || request.enabled());
    department.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
  }

  private long usageCount(DepartmentConfig department) {
    String name = department.getName();
    if (name == null || name.isBlank()) return 0;
    return accounts.countByDepartment(name) + roles.countByDefaultDepartment(name);
  }

  private String normalizeName(String value) {
    String text = Objects.toString(value, "").trim();
    return text.isBlank() ? null : text;
  }

  private String normalizeCode(String value, String name) {
    String raw = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
    if (!raw.isBlank()) return raw.replaceAll("[^A-Z0-9_]", "_");
    return "DEP_" + shortHash(name);
  }

  private String shortHash(String text) {
    try {
      byte[] digest = MessageDigest
        .getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < 4; i++) builder.append(String.format("%02X", digest[i]));
      return builder.toString();
    } catch (Exception e) {
      return Integer.toHexString(text.hashCode()).toUpperCase(Locale.ROOT);
    }
  }

  private String trimToNull(String value) {
    String text = Objects.toString(value, "").trim();
    return text.isBlank() ? null : text;
  }

  private String nonBlank(String value, String fallback) {
    String text = trimToNull(value);
    return text == null ? fallback : text;
  }

  public record DepartmentView(
    Long id,
    String code,
    String name,
    String parentCode,
    boolean enabled,
    Integer sortOrder,
    String source,
    long accountCount,
    long roleCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
  ) {}
}
