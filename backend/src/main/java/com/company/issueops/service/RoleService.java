package com.company.issueops.service;

import com.company.issueops.domain.RoleConfig;
import com.company.issueops.domain.UserAccount;
import com.company.issueops.repository.RoleConfigRepository;
import com.company.issueops.repository.UserAccountRepository;
import com.company.issueops.web.RoleRequest;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoleService {

  public static final String ACCOUNT_MANAGE = "account:manage";

  private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
  private static final Set<String> VALID_PERMISSIONS = Set.of(
    "issue:create",
    "issue:edit",
    "issue:delete",
    "issue:status",
    "issue:log",
    "field:manage",
    ACCOUNT_MANAGE,
    "ai:execute"
  );
  private static final Map<String, DefaultRole> DEFAULT_ROLES = Map.of(
    "ADMIN",
    new DefaultRole(
      "ADMIN",
      "管理员",
      "系统管理员，拥有账号、角色、字段和问题全量管理权限",
      List.of(
        "issue:create",
        "issue:edit",
        "issue:delete",
        "issue:status",
        "issue:log",
        "field:manage",
        ACCOUNT_MANAGE,
        "ai:execute"
      ),
      DataScopeService.ALL,
      "全部",
      10
    ),
    "PRODUCT",
    new DefaultRole(
      "PRODUCT",
      "产品",
      "负责产品缺陷治理、状态推进和 AI 操作确认",
      List.of("issue:create", "issue:edit", "issue:status", "issue:log", "ai:execute"),
      DataScopeService.ALL,
      "产品部",
      20
    ),
    "TECH",
    new DefaultRole(
      "TECH",
      "技术",
      "负责修复推进、状态更新和处理记录",
      List.of("issue:edit", "issue:status", "issue:log", "ai:execute"),
      DataScopeService.DEPARTMENT,
      "技术部",
      30
    ),
    "CS",
    new DefaultRole(
      "CS",
      "客服",
      "负责录入客户反馈和补充处理记录",
      List.of("issue:create", "issue:log"),
      DataScopeService.OWN,
      "客服部",
      40
    ),
    "VIEWER",
    new DefaultRole(
      "VIEWER",
      "观察员",
      "只读查看问题、数据和复盘信息",
      List.of(),
      DataScopeService.DEPARTMENT,
      "全部",
      50
    )
  );

  private final RoleConfigRepository roles;
  private final UserAccountRepository accounts;
  private final DataScopeService dataScopeService;

  @PostConstruct
  @Transactional
  public void ensureSystemRoles() {
    DEFAULT_ROLES.values().forEach(this::seedRole);
  }

  public List<RoleView> listRoles(boolean enabledOnly) {
    return (enabledOnly
        ? roles.findByEnabledTrueOrderBySortOrderAscIdAsc()
        : roles.findAllByOrderBySortOrderAscIdAsc())
      .stream()
      .map(this::toView)
      .toList();
  }

  @Transactional
  public RoleView create(RoleRequest request) {
    String code = normalizeCode(request.code());
    if (roles.existsByCode(code)) throw new IllegalArgumentException("角色编码已存在：" + code);

    RoleConfig role = new RoleConfig();
    role.setCode(code);
    role.setSystemBuiltin(false);
    applyMutation(role, request, true);
    return toView(roles.save(role));
  }

  @Transactional
  public RoleView update(Long id, RoleRequest request) {
    RoleConfig role = get(id);
    if (!Boolean.TRUE.equals(role.getSystemBuiltin())) {
      String code = normalizeCode(request.code());
      if (!Objects.equals(role.getCode(), code) && accounts.countByRole(role.getCode()) > 0) {
        throw new IllegalArgumentException("该角色已有账号使用，不能修改角色编码");
      }
      roles
        .findByCode(code)
        .filter(existing -> !Objects.equals(existing.getId(), id))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("角色编码已存在：" + code);
        });
      role.setCode(code);
    }
    applyMutation(role, request, false);
    return toView(roles.save(role));
  }

  @Transactional
  public RoleView enabled(Long id, boolean enabled) {
    RoleConfig role = get(id);
    if (Boolean.TRUE.equals(role.getSystemBuiltin())) {
      throw new IllegalArgumentException("内置系统角色不能停用");
    }
    if (!enabled && wouldRemoveLastAccountManager(role, List.of())) {
      throw new IllegalArgumentException("不能停用最后一个具备账号管理权限的角色");
    }
    if (!enabled && accounts.countByRole(role.getCode()) > 0) {
      throw new IllegalArgumentException("该角色已有账号使用，请先调整账号角色");
    }
    role.setEnabled(enabled);
    return toView(roles.save(role));
  }

  @Transactional
  public void delete(Long id) {
    RoleConfig role = get(id);
    if (Boolean.TRUE.equals(role.getSystemBuiltin())) {
      throw new IllegalArgumentException("内置系统角色不能删除");
    }
    long usageCount = accounts.countByRole(role.getCode());
    if (usageCount > 0) {
      throw new IllegalArgumentException("该角色已有账号使用，请先调整账号角色或停用角色");
    }
    roles.delete(role);
  }

  public String normalizeRole(String role) {
    String code = normalizeCode(role);
    RoleConfig config = roles
      .findByCode(code)
      .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
      .orElseThrow(() -> new IllegalArgumentException("无效或已停用角色：" + role));
    return config.getCode();
  }

  public List<String> permissionsFor(String role) {
    String code = normalizeNullableCode(role);
    if (code == null) return List.of();
    Optional<RoleConfig> configuredRole = roles.findByCode(code);
    if (configuredRole.isPresent()) {
      RoleConfig roleConfig = configuredRole.get();
      return Boolean.TRUE.equals(roleConfig.getEnabled())
        ? parsePermissions(roleConfig)
        : List.of();
    }
    return Optional
      .ofNullable(DEFAULT_ROLES.get(code))
      .map(DefaultRole::permissions)
      .orElse(List.of());
  }

  public boolean isRoleEnabled(String role) {
    String code = normalizeNullableCode(role);
    if (code == null) return false;
    return roles
      .findByCode(code)
      .map(RoleConfig::getEnabled)
      .map(Boolean.TRUE::equals)
      .orElse(DEFAULT_ROLES.containsKey(code));
  }

  public boolean hasPermission(String role, String permission) {
    return permissionsFor(role).contains(permission);
  }

  public String defaultScope(String role) {
    String code = normalizeNullableCode(role);
    if (code == null) return DataScopeService.DEPARTMENT;
    return roles
      .findByCode(code)
      .map(RoleConfig::getDefaultDataScope)
      .map(scope -> dataScopeService.normalizeScope(scope, code))
      .orElseGet(() ->
        Optional
          .ofNullable(DEFAULT_ROLES.get(code))
          .map(DefaultRole::defaultDataScope)
          .orElseGet(() -> dataScopeService.defaultScope(code))
      );
  }

  public String defaultDepartment(String role, String displayName) {
    String code = normalizeNullableCode(role);
    if (code == null) return Objects.toString(displayName, "未分配").trim();
    return roles
      .findByCode(code)
      .map(RoleConfig::getDefaultDepartment)
      .filter(value -> value != null && !value.isBlank())
      .orElseGet(() ->
        Optional
          .ofNullable(DEFAULT_ROLES.get(code))
          .map(DefaultRole::defaultDepartment)
          .orElseGet(() -> dataScopeService.defaultDepartment(code, displayName))
      );
  }

  public Map<String, String> permissionLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("issue:create", "新增问题");
    labels.put("issue:edit", "编辑问题");
    labels.put("issue:delete", "删除问题");
    labels.put("issue:status", "状态流转/复发标记");
    labels.put("issue:log", "新增处理记录");
    labels.put("field:manage", "字段配置");
    labels.put(ACCOUNT_MANAGE, "账号与角色管理");
    labels.put("ai:execute", "AI 草稿确认执行");
    return labels;
  }

  private void seedRole(DefaultRole seed) {
    Optional<RoleConfig> existing = roles.findByCode(seed.code());
    boolean creating = existing.isEmpty();
    RoleConfig role = existing.orElseGet(RoleConfig::new);
    role.setCode(seed.code());
    if (creating || role.getName() == null || role.getName().isBlank()) {
      role.setName(seed.name());
    }
    if (creating || role.getDescription() == null || role.getDescription().isBlank()) {
      role.setDescription(seed.description());
    }
    if (
      creating ||
      role.getDefaultDataScope() == null ||
      role.getDefaultDataScope().isBlank()
    ) {
      role.setDefaultDataScope(seed.defaultDataScope());
    }
    if (
      creating ||
      role.getDefaultDepartment() == null ||
      role.getDefaultDepartment().isBlank()
    ) {
      role.setDefaultDepartment(seed.defaultDepartment());
    }
    if (creating || role.getSortOrder() == null) {
      role.setSortOrder(seed.sortOrder());
    }
    role.setSystemBuiltin(true);
    role.setEnabled(true);
    if (role.getPermissions() == null || role.getPermissions().isBlank()) {
      role.setPermissions(String.join(",", seed.permissions()));
    }
    roles.save(role);
  }

  private void applyMutation(RoleConfig role, RoleRequest request, boolean creating) {
    String code = role.getCode();
    List<String> normalizedPermissions = normalizePermissions(code, request.permissions());
    if (wouldRemoveLastAccountManager(role, normalizedPermissions)) {
      throw new IllegalArgumentException("不能移除最后一个账号管理角色的管理权限");
    }
    role.setName(nonBlank(request.name(), code));
    role.setDescription(trimToNull(request.description()));
    role.setPermissions(String.join(",", normalizedPermissions));
    role.setDefaultDataScope(
      dataScopeService.normalizeScope(request.defaultDataScope(), code)
    );
    role.setDefaultDepartment(
      nonBlank(request.defaultDepartment(), defaultDepartment(code, request.name()))
    );
    role.setSortOrder(request.sortOrder() == null ? role.getSortOrder() : request.sortOrder());
    if (Boolean.TRUE.equals(role.getSystemBuiltin())) {
      role.setEnabled(true);
    } else if (creating) {
      role.setEnabled(request.enabled() == null || request.enabled());
    } else if (request.enabled() != null) {
      role.setEnabled(request.enabled());
    }
  }

  private List<String> normalizePermissions(String roleCode, List<String> permissions) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (permissions != null) {
      permissions
        .stream()
        .map(value -> Objects.toString(value, "").trim())
        .filter(value -> !value.isBlank())
        .forEach(value -> {
          if (!VALID_PERMISSIONS.contains(value)) {
            throw new IllegalArgumentException("不支持的权限：" + value);
          }
          normalized.add(value);
        });
    }
    if ("ADMIN".equals(roleCode)) {
      normalized.add(ACCOUNT_MANAGE);
    }
    return List.copyOf(normalized);
  }

  private RoleConfig get(Long id) {
    return roles
      .findById(id)
      .orElseThrow(() -> new NoSuchElementException("角色不存在"));
  }

  private RoleView toView(RoleConfig role) {
    return new RoleView(
      role.getId(),
      role.getCode(),
      role.getName(),
      role.getDescription(),
      parsePermissions(role),
      dataScopeService.normalizeScope(role.getDefaultDataScope(), role.getCode()),
      role.getDefaultDepartment(),
      Boolean.TRUE.equals(role.getEnabled()),
      Boolean.TRUE.equals(role.getSystemBuiltin()),
      role.getSortOrder(),
      accounts.countByRole(role.getCode()),
      role.getCreatedAt(),
      role.getUpdatedAt()
    );
  }

  private List<String> parsePermissions(RoleConfig role) {
    if (role.getPermissions() == null || role.getPermissions().isBlank()) return List.of();
    return Arrays
      .stream(role.getPermissions().split(","))
      .map(String::trim)
      .filter(value -> !value.isBlank())
      .distinct()
      .toList();
  }

  private boolean wouldRemoveLastAccountManager(
    RoleConfig role,
    List<String> nextPermissions
  ) {
    if (!parsePermissions(role).contains(ACCOUNT_MANAGE)) return false;
    if (nextPermissions.contains(ACCOUNT_MANAGE)) return false;
    String roleCode = role.getCode();
    boolean hasEnabledAccountUsingRole = accounts
      .findAll()
      .stream()
      .anyMatch(account ->
        Boolean.TRUE.equals(account.getEnabled()) &&
        Objects.equals(normalizeNullableCode(account.getRole()), roleCode)
      );
    return hasEnabledAccountUsingRole && !otherEnabledAccountManagerExists(roleCode);
  }

  private boolean otherEnabledAccountManagerExists(String excludedRoleCode) {
    return accounts
      .findAll()
      .stream()
      .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
      .filter(account ->
        !Objects.equals(normalizeNullableCode(account.getRole()), excludedRoleCode)
      )
      .map(UserAccount::getRole)
      .anyMatch(role -> permissionsFor(role).contains(ACCOUNT_MANAGE));
  }

  private String normalizeCode(String value) {
    String code = normalizeNullableCode(value);
    if (code == null || !ROLE_CODE_PATTERN.matcher(code).matches()) {
      throw new IllegalArgumentException("角色编码必须为 2-30 位大写字母、数字或下划线，且以字母开头");
    }
    return code;
  }

  private String normalizeNullableCode(String value) {
    String code = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
    return code.isBlank() ? null : code;
  }

  private String nonBlank(String value, String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record DefaultRole(
    String code,
    String name,
    String description,
    List<String> permissions,
    String defaultDataScope,
    String defaultDepartment,
    int sortOrder
  ) {}

  public record RoleView(
    Long id,
    String code,
    String name,
    String description,
    List<String> permissions,
    String defaultDataScope,
    String defaultDepartment,
    boolean enabled,
    boolean systemBuiltin,
    Integer sortOrder,
    long accountCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
  ) {}
}
