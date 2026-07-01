package com.company.issueops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

  public static final String REQUEST_USER_ATTRIBUTE = "currentUser";

  private final ObjectMapper objectMapper;

  @Value("${auth.secret}")
  private String secret;

  @Value("${auth.token-ttl-seconds}")
  private long tokenTtlSeconds;

  @Value("${auth.users}")
  private String usersConfig;

  private final Map<String, Account> accounts = new LinkedHashMap<>();

  @PostConstruct
  void init() {
    accounts.clear();
    Arrays
      .stream(usersConfig.split(";"))
      .map(String::trim)
      .filter(item -> !item.isBlank())
      .map(this::parseAccount)
      .forEach(account -> accounts.put(account.username(), account));
  }

  public AuthSession login(String username, String password) {
    Account account = accounts.get(normalize(username));
    if (account == null || !Objects.equals(account.password(), password)) {
      throw new IllegalArgumentException("账号或密码不正确");
    }
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
      Account account = accounts.get(normalize(username));
      if (account == null) return Optional.empty();
      return Optional.of(toUser(account));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public boolean canAccess(AuthUser user, String method, String path) {
    if ("OPTIONS".equalsIgnoreCase(method)) return true;
    if ("GET".equalsIgnoreCase(method)) return true;
    if (path.startsWith("/api/auth/")) return true;

    if (path.startsWith("/api/dictionaries")) {
      return hasPermission(user, "field:manage");
    }
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
    return user.permissions().contains(permission);
  }

  private Account parseAccount(String raw) {
    String[] parts = raw.split("\\|", 4);
    if (parts.length != 4) {
      throw new IllegalArgumentException("AUTH_USERS 配置格式错误");
    }
    return new Account(
      normalize(parts[0]),
      parts[1],
      parts[2].trim().toUpperCase(Locale.ROOT),
      parts[3].trim()
    );
  }

  private AuthUser toUser(Account account) {
    return new AuthUser(
      account.username(),
      account.displayName(),
      account.role(),
      permissionsFor(account.role())
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

  private record Account(
    String username,
    String password,
    String role,
    String displayName
  ) {}

  public record AuthUser(
    String username,
    String displayName,
    String role,
    List<String> permissions
  ) {}

  public record AuthSession(String token, AuthUser user, long expiresAt) {}
}
