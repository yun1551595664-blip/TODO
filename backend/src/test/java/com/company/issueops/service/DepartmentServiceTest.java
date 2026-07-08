package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.DepartmentConfig;
import com.company.issueops.repository.DepartmentConfigRepository;
import com.company.issueops.repository.RoleConfigRepository;
import com.company.issueops.repository.UserAccountRepository;
import com.company.issueops.web.DepartmentRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DepartmentServiceTest {

  @Test
  void createsManualDepartment() {
    DepartmentConfigRepository departments = mock(DepartmentConfigRepository.class);
    when(departments.findByCode("OPS")).thenReturn(Optional.empty());
    when(departments.findByName("Ops")).thenReturn(Optional.empty());
    when(departments.save(any(DepartmentConfig.class))).thenAnswer(invocation -> {
      DepartmentConfig department = invocation.getArgument(0);
      department.setId(10L);
      return department;
    });

    DepartmentService service = new DepartmentService(
      departments,
      mock(UserAccountRepository.class),
      mock(RoleConfigRepository.class)
    );

    DepartmentService.DepartmentView view = service.create(
      new DepartmentRequest("OPS", "Ops", null, true, 40)
    );

    assertThat(view.id()).isEqualTo(10L);
    assertThat(view.code()).isEqualTo("OPS");
    assertThat(view.name()).isEqualTo("Ops");
    assertThat(view.source()).isEqualTo("MANUAL");
    assertThat(view.enabled()).isTrue();
  }

  @Test
  void rejectsDisablingDepartmentUsedByAccounts() {
    DepartmentConfig department = department(1L, "OPS", "Ops");
    DepartmentConfigRepository departments = mock(DepartmentConfigRepository.class);
    when(departments.findById(1L)).thenReturn(Optional.of(department));

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.countByDepartment("Ops")).thenReturn(1L);

    DepartmentService service = new DepartmentService(
      departments,
      accounts,
      mock(RoleConfigRepository.class)
    );

    assertThatThrownBy(() -> service.setEnabled(1L, false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("in use");
  }

  @Test
  void updateKeepsExistingDepartmentCode() {
    DepartmentConfig department = department(1L, "OPS", "Ops");
    DepartmentConfigRepository departments = mock(DepartmentConfigRepository.class);
    when(departments.findById(1L)).thenReturn(Optional.of(department));
    when(departments.findByCode("OPS")).thenReturn(Optional.of(department));
    when(departments.findByName("Operations")).thenReturn(Optional.empty());
    when(departments.save(any(DepartmentConfig.class))).thenAnswer(invocation ->
      invocation.getArgument(0)
    );

    DepartmentService service = new DepartmentService(
      departments,
      mock(UserAccountRepository.class),
      mock(RoleConfigRepository.class)
    );

    DepartmentService.DepartmentView view = service.update(
      1L,
      new DepartmentRequest("NEW_CODE", "Operations", null, true, 20)
    );

    assertThat(view.code()).isEqualTo("OPS");
    assertThat(view.name()).isEqualTo("Operations");
  }

  @Test
  void rejectsDeletingDepartmentUsedByRoleDefaults() {
    DepartmentConfig department = department(1L, "OPS", "Ops");
    DepartmentConfigRepository departments = mock(DepartmentConfigRepository.class);
    when(departments.findById(1L)).thenReturn(Optional.of(department));

    RoleConfigRepository roles = mock(RoleConfigRepository.class);
    when(roles.countByDefaultDepartment("Ops")).thenReturn(1L);

    DepartmentService service = new DepartmentService(
      departments,
      mock(UserAccountRepository.class),
      roles
    );

    assertThatThrownBy(() -> service.delete(1L))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("in use");
  }

  @Test
  void deletesUnusedDepartment() {
    DepartmentConfig department = department(1L, "OPS", "Ops");
    DepartmentConfigRepository departments = mock(DepartmentConfigRepository.class);
    when(departments.findById(1L)).thenReturn(Optional.of(department));

    DepartmentService service = new DepartmentService(
      departments,
      mock(UserAccountRepository.class),
      mock(RoleConfigRepository.class)
    );

    service.delete(1L);

    verify(departments).delete(department);
  }

  private DepartmentConfig department(Long id, String code, String name) {
    DepartmentConfig department = new DepartmentConfig();
    department.setId(id);
    department.setCode(code);
    department.setName(name);
    department.setEnabled(true);
    department.setSortOrder(10);
    department.setSource("MANUAL");
    return department;
  }
}
