package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.RoleConfig;
import com.company.issueops.domain.UserAccount;
import com.company.issueops.repository.RoleConfigRepository;
import com.company.issueops.repository.UserAccountRepository;
import com.company.issueops.web.RoleRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoleServiceTest {

  @Test
  void rejectsChangingRoleCodeWhenAccountsUseRole() {
    RoleConfig role = role(10L, "OPS", "运营", "issue:create", false);
    RoleConfigRepository roles = mock(RoleConfigRepository.class);
    when(roles.findById(10L)).thenReturn(Optional.of(role));

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.countByRole("OPS")).thenReturn(1L);

    RoleService service = new RoleService(roles, accounts, new DataScopeService());

    assertThatThrownBy(() ->
      service.update(
        10L,
        new RoleRequest(
          "OPS_MANAGER",
          "运营负责人",
          "",
          List.of("issue:create"),
          "DEPARTMENT",
          "运营部",
          true,
          60
        )
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("不能修改角色编码");
  }

  @Test
  void disabledRoleCannotBeAssignedOrGrantPermissions() {
    RoleConfig role = role(
      1L,
      "OPS_MANAGER",
      "运营经理",
      RoleService.ACCOUNT_MANAGE,
      false
    );
    RoleConfigRepository roles = mock(RoleConfigRepository.class);
    when(roles.findByCode("OPS_MANAGER")).thenReturn(Optional.of(role));

    RoleService service = new RoleService(
      roles,
      mock(UserAccountRepository.class),
      new DataScopeService()
    );

    assertThat(service.permissionsFor("OPS_MANAGER"))
      .containsExactly(RoleService.ACCOUNT_MANAGE);

    role.setEnabled(false);

    assertThat(service.permissionsFor("OPS_MANAGER")).isEmpty();
    assertThatThrownBy(() -> service.normalizeRole("OPS_MANAGER"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("无效或已停用角色");
  }

  @Test
  void cannotRemoveLastAccountManagerPermissionFromRoleInUse() {
    RoleConfig role = role(
      1L,
      "OPS_MANAGER",
      "运营经理",
      RoleService.ACCOUNT_MANAGE,
      false
    );
    UserAccount manager = account("ops", "OPS_MANAGER", true);

    RoleConfigRepository roles = mock(RoleConfigRepository.class);
    when(roles.findById(1L)).thenReturn(Optional.of(role));
    when(roles.findByCode("OPS_MANAGER")).thenReturn(Optional.of(role));

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findAll()).thenReturn(List.of(manager));

    RoleService service = new RoleService(roles, accounts, new DataScopeService());

    RoleRequest request = new RoleRequest(
      "OPS_MANAGER",
      "运营经理",
      "负责运营问题治理",
      List.of("issue:create"),
      DataScopeService.DEPARTMENT,
      "运营部",
      true,
      10
    );

    assertThatThrownBy(() -> service.update(1L, request))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("不能移除最后一个账号管理角色的管理权限");
  }

  @Test
  void systemRoleSeedKeepsExistingBackendEdits() {
    Map<String, RoleConfig> store = new HashMap<>();
    RoleConfig admin = role(
      1L,
      "ADMIN",
      "超级管理员",
      "issue:create,account:manage",
      false
    );
    admin.setDescription("后台已调整");
    admin.setDefaultDataScope(DataScopeService.ALL);
    admin.setDefaultDepartment("管理中心");
    admin.setSortOrder(99);
    store.put("ADMIN", admin);

    RoleConfigRepository roles = mock(RoleConfigRepository.class);
    when(roles.findByCode(any())).thenAnswer(invocation ->
      Optional.ofNullable(store.get(invocation.getArgument(0)))
    );
    when(roles.save(any(RoleConfig.class))).thenAnswer(invocation -> {
      RoleConfig role = invocation.getArgument(0);
      store.put(role.getCode(), role);
      return role;
    });

    RoleService service = new RoleService(
      roles,
      mock(UserAccountRepository.class),
      new DataScopeService()
    );

    service.ensureSystemRoles();

    RoleConfig seededAdmin = store.get("ADMIN");
    assertThat(seededAdmin.getName()).isEqualTo("超级管理员");
    assertThat(seededAdmin.getDescription()).isEqualTo("后台已调整");
    assertThat(seededAdmin.getDefaultDepartment()).isEqualTo("管理中心");
    assertThat(seededAdmin.getSortOrder()).isEqualTo(99);
    assertThat(seededAdmin.getPermissions()).isEqualTo("issue:create,account:manage");
    assertThat(seededAdmin.getSystemBuiltin()).isTrue();
    assertThat(seededAdmin.getEnabled()).isTrue();
  }

  private RoleConfig role(
    Long id,
    String code,
    String name,
    String permissions,
    boolean systemBuiltin
  ) {
    RoleConfig role = new RoleConfig();
    role.setId(id);
    role.setCode(code);
    role.setName(name);
    role.setDescription(name);
    role.setPermissions(permissions);
    role.setDefaultDataScope(DataScopeService.DEPARTMENT);
    role.setDefaultDepartment("运营部");
    role.setSortOrder(10);
    role.setEnabled(true);
    role.setSystemBuiltin(systemBuiltin);
    return role;
  }

  private UserAccount account(String username, String role, boolean enabled) {
    UserAccount account = new UserAccount();
    account.setUsername(username);
    account.setDisplayName(username);
    account.setRole(role);
    account.setDataScope(DataScopeService.ALL);
    account.setEnabled(enabled);
    account.setPasswordHash("pbkdf2$mock");
    return account;
  }
}
