package com.company.issueops.service;

import com.company.issueops.domain.UserAccount;
import com.company.issueops.repository.UserAccountRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  public static final String REQUEST_USER_ATTRIBUTE = "currentUser";

  private static final Set<String> ROLES = Set.of(
    "ADMIN",
    "PRODUCT",
    "TECH",
    "CS",
    "VIEWER"
  );

  private final ObjectMapper objectMapper;
  private final UserAccountRepository accounts;
  private final PasswordHashService passwordHashService;
  private final DataScopeService dataScopeService;

  @Value("${auth.secret}")
  private String secret;

  @Value("${auth.token-ttl-seconds}")
  private long tokenTtlSeconds;

  @Value("${auth.users}")
  private String usersConfig;

  @Value("${auth.sso.enabled:false}")
  private boolean ssoEnabled;

  @Value("${auth.sso.provider-name:企业 SSO}")
  private String ssoProviderName;

  @Value("${auth.sso.login-url:}")
  private String ssoLoginUrl;

  @PostConstruct
  void init() {
    syncConfiguredAccounts();
  }

  @Transactional
  public AuthSession login(String username, String password) {
    UserAccount account = accounts
      .findByUsername(normalize(username))
      .orElseThrow(() -> new IllegalArgumentException("账号或密码不正确"));
    if (!Boolean.TRUE.equals(account.getEnabled())) {
      throw new IllegalArgumentException("账号已停用，请联系管理员");
    }
    if (!passwordHashService.matches(password, account.getPasswordHash())) {
      throw new IllegalArgumentException("账号或密码不正确");
    }
    account.setLastLoginAt(LocalDateTime.now());
    accounts.save(account);
    long expiresAt = Instant.now().plusSeconds(tokenTtlSeconds).getEpochSecond();
    AuthUser user = toUser(account);
    return new AuthSession(issueToken(user, expiresAt), user, expiresAt);
  }

  public Optional<AuthUser> authenticate(String authorizationHeader) {
    if (
      authorizationHeader == null ||
      !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)
    ) {
      return Optional.empty();
    }
    String token = authorizationHeader.substring(7).trim();
    String[] parts = token.split("\\.", 2);
    if (parts.length != 2) return Optional.empty();

    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
      String expectedSignature = sign(payload);
      if (!constantTimeEquals(expectedSignature, parts[1])) return Optional.empty();

      Map<String, Object> claims = objectMapper.readValue(
        payload,
        new TypeReference<>() {}
      );
      long expiresAt = ((Number) claims.getOrDefault("exp", 0)).longValue();
      if (expiresAt < Instant.now().getEpochSecond()) return Optional.empty();

      String username = Objects.toString(claims.get("username"), "");
      return accounts
        .findByUsername(normalize(username))
        .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
        .map(this::toUser);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public boolean canAccess(AuthUser user, String method, String path) {
    if ("OPTIONS".equalsIgnoreCase(method)) return true;
    if (path.startsWith("/api/auth/")) return true;

    if (path.startsWith("/api/accounts")) {
      return hasPermission(user, "account:manage");
    }
    if (path.startsWith("/api/dictionaries")) {
      return hasPermission(user, "field:manage");
    }
    if ("GET".equalsIgnoreCase(method)) return true;
    if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/issues/\\d+")) {
      return hasPermission(user, "issue:delete");
    }
    if ("POST".equalsIgnoreCase(method) && path.equals("/api/issues")) {
      return hasPermission(user, "issue:create");
    }
    if ("PUT".equalsIgnoreCase(method) && path.matches("/api/issues/\\d+")) {
      return hasPermission(user, "issue:edit");
    }
    if (path.matches("/api/issues/\\d+/status")) {
      return hasPermission(user, "issue:status");
    }
    if (path.matches("/api/issues/\\d+/reopened")) {
      return hasPermission(user, "issue:status");
    }
    if (path.matches("/api/issues/\\d+/logs")) {
      return hasPermission(user, "issue:log");
    }
    if (path.equals("/api/ai-insights/actions/execute")) {
      return hasPermission(user, "ai:execute");
    }

    return true;
  }

  public boolean hasPermission(AuthUser user, String permission) {
    return user != null && user.permissions().contains(permission);
  }

  public List<AccountView> listAccounts() {
    return accounts.findAll().stream().map(this::toView).toList();
  }

  @Transactional
  public AccountView createAccount(AccountMutation mutation) {
    String username = normalize(mutation.username());
    if (username.isBlank()) throw new IllegalArgumentException("账号不能为空");
    if (accounts.existsByUsername(username)) {
      throw new IllegalArgumentException("账号已存在：" + username);
    }
    requirePassword(mutation.password(), true);
    UserAccount account = new UserAccount();
    account.setUsername(username);
    account.setPasswordHash(passwordHashService.hash(mutation.password()));
    applyMutation(account, mutation, true);
    return toView(accounts.save(account));
  }

  @Transactional
  public AccountView updateAccount(Long id, AccountMutation mutation) {
    UserAccount account = getAccount(id);
    boolean disabling = Boolean.FALSE.equals(mutation.enabled());
    if (disabling && isLastEnabledAdmin(account)) {
      throw new IllegalArgumentException("不能停用最后一个启用的管理员账号");
    }
    if (mutation.password() != null && !mutation.password().isBlank()) {
      requirePassword(mutation.password(), false);
      account.setPasswordHash(passwordHashService.hash(mutation.password()));
    }
    applyMutation(account, mutation, false);
    return toView(accounts.save(account));
  }

  @Transactional
  public AccountView enabled(Long id, boolean enabled) {
    UserAccount account = getAccount(id);
    if (!enabled && isLastEnabledAdmin(account)) {
      throw new IllegalArgumentException("不能停用最后一个启用的管理员账号");
    }
    account.setEnabled(enabled);
    return toView(accounts.save(account));
  }

  public SsoConfig ssoConfig() {
    return new SsoConfig(ssoEnabled, ssoProviderName);
  }

  public SsoLoginResponse ssoLogin() {
    if (!ssoEnabled || ssoLoginUrl == null || ssoLoginUrl.isBlank()) {
      throw new IllegalStateException("企业 SSO 尚未启用");
    }
    return new SsoLoginResponse(ssoProviderName, ssoLoginUrl);
  }

  private void syncConfiguredAccounts() {
    Arrays
      .stream(usersConfig.split(";"))
      .map(String::trim)
      .filter(item -> !item.isBlank())
      .map(this::parseSeedAccount)
      .forEach(this::syncConfiguredAccount);
  }

  private void syncConfiguredAccount(SeedAccount seed) {
    Optional<UserAccount> existing = accounts.findByUsername(seed.username());
    if (existing.isEmpty()) {
      UserAccount account = new UserAccount();
      account.setUsername(seed.username());
      account.setDisplayName(seed.displayName());
      account.setRole(seed.role());
      account.setDepartment(seed.department());
      account.setDataScope(seed.dataScope());
      account.setPasswordHash(passwordHashService.hash(seed.password()));
      account.setEnabled(true);
      accounts.save(account);
      return;
    }

    UserAccount account = existing.get();
    boolean changed = false;
    if (account.getPasswordHash() == null || account.getPasswordHash().isBlank()) {
      account.setPasswordHash(passwordHashService.hash(seed.password()));
      changed = true;
    } else if (!passwordHashService.isHashed(account.getPasswordHash())) {
      account.setPasswordHash(passwordHashService.hash(account.getPasswordHash()));
      changed = true;
    }
    if (account.getRole() == null || account.getRole().isBlank()) {
      account.setRole(seed.role());
      changed = true;
    }
    if (account.getDisplayName() == null || account.getDisplayName().isBlank()) {
      account.setDisplayName(seed.displayName());
      changed = true;
    }
    if (account.getDepartment() == null || account.getDepartment().isBlank()) {
      account.setDepartment(seed.department());
      changed = true;
    }
    if (account.getDataScope() == null || account.getDataScope().isBlank()) {
      account.setDataScope(seed.dataScope());
      changed = true;
    }
    if (account.getEnabled() == null) {
      account.setEnabled(true);
      changed = true;
    }
    if (changed) accounts.save(account);
  }

  private UserAccount getAccount(Long id) {
    return accounts
      .findById(id)
      .orElseThrow(() -> new NoSuchElementException("账号不存在"));
  }

  private void applyMutation(
    UserAccount account,
    AccountMutation mutation,
    boolean creating
  ) {
    String role = normalizeRole(mutation.role());
    account.setDisplayName(nonBlank(mutation.displayName(), account.getUsername()));
    account.setRole(role);
    account.setDepartment(
      nonBlank(
        mutation.department(),
        dataScopeService.defaultDepartment(role, account.getDisplayName())
      )
    );
    account.setDataScope(dataScopeService.normalizeScope(mutation.dataScope(), role));
    account.setSsoSubject(blankToNull(mutation.ssoSubject()));
    if (creating) {
      account.setEnabled(mutation.enabled() == null || mutation.enabled());
    } else if (mutation.enabled() != null) {
      account.setEnabled(mutation.enabled());
    }
  }

  private void requirePassword(String password, boolean creating) {
    if (password == null || password.isBlank()) {
      if (creating) throw new IllegalArgumentException("密码不能为空");
      return;
    }
    if (password.length() < 8) throw new IllegalArgumentException("密码至少 8 位");
  }

  private boolean isLastEnabledAdmin(UserAccount account) {
    return (
      "ADMIN".equals(account.getRole()) &&
      Boolean.TRUE.equals(account.getEnabled()) &&
      accounts.countByRoleAndEnabledTrue("ADMIN") <= 1
    );
  }

  private SeedAccount parseSeedAccount(String raw) {
    String[] parts = raw.split("\\|", -1);
    if (parts.length < 4 || parts.length > 6) {
      throw new IllegalArgumentException("AUTH_USERS 配置格式错误");
    }
    String role = normalizeRole(parts[2]);
    String displayName = parts[3].trim();
    String department = parts.length >= 5 && !parts[4].isBlank()
      ? parts[4].trim()
      : dataScopeService.defaultDepartment(role, displayName);
    String dataScope = parts.length >= 6
      ? dataScopeService.normalizeScope(parts[5], role)
      : dataScopeService.defaultScope(role);
    return new SeedAccount(normalize(parts[0]), parts[1], role, displayName, department, dataScope);
  }

  private AuthUser toUser(UserAccount account) {
    return new AuthUser(
      account.getUsername(),
      account.getDisplayName(),
      account.getRole(),
      account.getDepartment(),
      dataScopeService.normalizeScope(account.getDataScope(), account.getRole()),
      permissionsFor(account.getRole())
    );
  }

  private AccountView toView(UserAccount account) {
    return new AccountView(
      account.getId(),
      account.getUsername(),
      account.getDisplayName(),
      account.getRole(),
      account.getDepartment(),
      dataScopeService.normalizeScope(account.getDataScope(), account.getRole()),
      Boolean.TRUE.equals(account.getEnabled()),
      account.getSsoSubject(),
      account.getLastLoginAt(),
      account.getCreatedAt(),
      account.getUpdatedAt()
    );
  }

  private List<String> permissionsFor(String role) {
    return switch (role) {
      case "ADMIN" -> List.of(
        "issue:create",
        "issue:edit",
        "issue:delete",
        "issue:status",
        "issue:log",
        "field:manage",
        "account:manage",
        "ai:execute"
      );
      case "PRODUCT" -> List.of(
        "issue:create",
        "issue:edit",
        "issue:status",
        "issue:log",
        "ai:execute"
      );
      case "TECH" -> List.of("issue:edit", "issue:status", "issue:log", "ai:execute");
      case "CS" -> List.of("issue:create", "issue:log");
      default -> List.of();
    };
  }

  private String issueToken(AuthUser user, long expiresAt) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("username", user.username());
      payload.put("role", user.role());
      payload.put("exp", expiresAt);
      String json = objectMapper.writeValueAsString(payload);
      return (
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)) +
        "." +
        sign(json)
      );
    } catch (Exception e) {
      throw new IllegalStateException("生成登录凭证失败", e);
    }
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    } catch (Exception e) {
      throw new IllegalStateException("签名登录凭证失败", e);
    }
  }

  private boolean constantTimeEquals(String expected, String actual) {
    return MessageDigest.isEqual(
      expected.getBytes(StandardCharsets.UTF_8),
      actual.getBytes(StandardCharsets.UTF_8)
    );
  }

  private String normalize(String username) {
    return Objects.toString(username, "").trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeRole(String role) {
    String normalized = Objects.toString(role, "").trim().toUpperCase(Locale.ROOT);
    if (!ROLES.contains(normalized)) throw new IllegalArgumentException("无效角色：" + role);
    return normalized;
  }

  private String nonBlank(String value, String fallback) {
    String text = Objects.toString(value, "").trim();
    return text.isBlank() ? fallback : text;
  }

  private String blankToNull(String value) {
    String text = Objects.toString(value, "").trim();
    return text.isBlank() ? null : text;
  }

  private record SeedAccount(
    String username,
    String password,
    String role,
    String displayName,
    String department,
    String dataScope
  ) {}

  public record AccountMutation(
    String username,
    String password,
    String displayName,
    String role,
    Boolean enabled,
    String department,
    String dataScope,
    String ssoSubject
  ) {}

  public record AccountView(
    Long id,
    String username,
    String displayName,
    String role,
    String department,
    String dataScope,
    boolean enabled,
    String ssoSubject,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
  ) {}

  public record AuthUser(
    String username,
    String displayName,
    String role,
    String department,
    String dataScope,
    List<String> permissions
  ) {}

  public record AuthSession(String token, AuthUser user, long expiresAt) {}

  public record SsoConfig(boolean enabled, String providerName) {}

  public record SsoLoginResponse(String providerName, String loginUrl) {}
}
