package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.UserAccount;
import com.company.issueops.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

  private final Map<String, UserAccount> store = new LinkedHashMap<>();
  private AuthService service;

  @BeforeEach
  void setUp() {
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findByUsername(anyString())).thenAnswer(invocation ->
      Optional.ofNullable(store.get(invocation.getArgument(0)))
    );
    when(accounts.existsByUsername(anyString())).thenAnswer(invocation ->
      store.containsKey(invocation.getArgument(0))
    );
    when(accounts.countByRoleAndEnabledTrue(anyString())).thenAnswer(invocation ->
      store
        .values()
        .stream()
        .filter(account -> invocation.getArgument(0).equals(account.getRole()))
        .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
        .count()
    );
    when(accounts.save(any(UserAccount.class))).thenAnswer(invocation -> {
      UserAccount account = invocation.getArgument(0);
      if (account.getId() == null) account.setId((long) store.size() + 1);
      store.put(account.getUsername(), account);
      return account;
    });

    service =
      new AuthService(new ObjectMapper(), accounts, new PasswordHashService());
    ReflectionTestUtils.setField(service, "secret", "unit-test-secret");
    ReflectionTestUtils.setField(service, "tokenTtlSeconds", 3600L);
    ReflectionTestUtils.setField(
      service,
      "usersConfig",
      "admin|admin123456|ADMIN|照远;viewer|viewer123456|VIEWER|观察员"
    );
    ReflectionTestUtils.setField(service, "ssoEnabled", false);
    ReflectionTestUtils.setField(service, "ssoProviderName", "企业 SSO");
    ReflectionTestUtils.setField(service, "ssoLoginUrl", "");
    service.init();
  }

  @Test
  void loginIssuesBearerTokenAndAuthenticatesUser() {
    AuthService.AuthSession session = service.login("admin", "admin123456");

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
  void disabledAccountCannotLoginOrAuthenticateExistingToken() {
    AuthService.AuthSession session = service.login("viewer", "viewer123456");
    store.get("viewer").setEnabled(false);

    assertThatThrownBy(() -> service.login("viewer", "viewer123456"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("账号已停用");
    assertThat(service.authenticate("Bearer " + session.token())).isEmpty();
  }

  @Test
  void protectsWritePermissionsByRole() {
    AuthService.AuthUser admin = service.login("admin", "admin123456").user();
    AuthService.AuthUser viewer = service.login("viewer", "viewer123456").user();

    assertThat(service.canAccess(admin, "DELETE", "/api/issues/1")).isTrue();
    assertThat(service.canAccess(viewer, "GET", "/api/issues")).isTrue();
    assertThat(service.canAccess(viewer, "DELETE", "/api/issues/1")).isFalse();
    assertThat(service.canAccess(viewer, "POST", "/api/dictionaries")).isFalse();
    assertThat(service.canAccess(viewer, "GET", "/api/accounts")).isFalse();
  }

  @Test
  void adminCanCreateHashedAccounts() {
    AuthService.AccountView account = service.createAccount(
      new AuthService.AccountMutation(
        "ops",
        "ops123456",
        "运营负责人",
        "PRODUCT",
        true,
        null
      )
    );

    assertThat(account.username()).isEqualTo("ops");
    assertThat(store.get("ops").getPasswordHash()).startsWith("pbkdf2$");
    assertThat(service.login("ops", "ops123456").user().role()).isEqualTo("PRODUCT");
  }
}
