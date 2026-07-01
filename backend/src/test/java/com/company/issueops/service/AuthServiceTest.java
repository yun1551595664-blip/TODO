package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

  private AuthService service;

  @BeforeEach
  void setUp() {
    service = new AuthService(new ObjectMapper());
    ReflectionTestUtils.setField(service, "secret", "unit-test-secret");
    ReflectionTestUtils.setField(service, "tokenTtlSeconds", 3600L);
    ReflectionTestUtils.setField(
      service,
      "usersConfig",
      "admin|admin123|ADMIN|照远;viewer|viewer123|VIEWER|观察员"
    );
    service.init();
  }

  @Test
  void loginIssuesBearerTokenAndAuthenticatesUser() {
    AuthService.AuthSession session = service.login("admin", "admin123");

    assertThat(session.token()).contains(".");
    assertThat(session.user().displayName()).isEqualTo("照远");
    assertThat(
      service.authenticate("Bearer " + session.token()).orElseThrow().role()
    ).isEqualTo("ADMIN");
  }

  @Test
  void rejectsWrongPassword() {
    assertThatThrownBy(() -> service.login("admin", "wrong"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("账号或密码不正确");
  }

  @Test
  void protectsWritePermissionsByRole() {
    AuthService.AuthUser admin = service.login("admin", "admin123").user();
    AuthService.AuthUser viewer = service.login("viewer", "viewer123").user();

    assertThat(service.canAccess(admin, "DELETE", "/api/issues/1")).isTrue();
    assertThat(service.canAccess(viewer, "GET", "/api/issues")).isTrue();
    assertThat(service.canAccess(viewer, "DELETE", "/api/issues/1")).isFalse();
    assertThat(service.canAccess(viewer, "POST", "/api/dictionaries")).isFalse();
  }
}
