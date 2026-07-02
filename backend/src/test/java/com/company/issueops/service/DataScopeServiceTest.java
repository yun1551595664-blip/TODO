package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.issueops.domain.Issue;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataScopeServiceTest {

  private final DataScopeService service = new DataScopeService();

  @Test
  void departmentScopeSeesDepartmentCreatedAndAssignedIssues() {
    AuthService.AuthUser user = user("PRODUCT", "产品部", "DEPARTMENT");
    Issue department = issue(1L, "产品部", "技术同学", "客服同学");
    Issue created = issue(2L, "技术部", "技术同学", "照远");
    Issue assigned = issue(3L, "技术部", "照远", "客服同学");
    Issue hidden = issue(4L, "技术部", "技术同学", "客服同学");

    assertThat(service.filterVisible(user, List.of(department, created, assigned, hidden)))
      .extracting(Issue::getId)
      .containsExactly(1L, 2L, 3L);
  }

  @Test
  void ownScopeOnlySeesCreatedIssues() {
    AuthService.AuthUser user = user("CS", "客服部", "OWN");
    Issue created = issue(1L, "技术部", "技术同学", "照远");
    Issue assigned = issue(2L, "技术部", "照远", "客服同学");

    assertThat(service.filterVisible(user, List.of(created, assigned)))
      .extracting(Issue::getId)
      .containsExactly(1L);
  }

  private AuthService.AuthUser user(String role, String department, String dataScope) {
    return new AuthService.AuthUser(
      "zaoyuan",
      "照远",
      role,
      department,
      dataScope,
      List.of()
    );
  }

  private Issue issue(Long id, String department, String owner, String createdBy) {
    Issue issue = new Issue();
    issue.setId(id);
    issue.setResponsibleDepartment(department);
    issue.setResponsiblePerson(owner);
    issue.setCreatedBy(createdBy);
    issue.setDeleted(false);
    return issue;
  }
}
