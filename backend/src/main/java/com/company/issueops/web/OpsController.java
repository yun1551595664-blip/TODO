package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class OpsController {

  private static final String DEFAULT_AUTH_SECRET = "dev-local-issue-ops-secret-change-me";

  private final JdbcTemplate jdbcTemplate;

  @Value("${ai.provider:}")
  private String aiProvider;

  @Value("${ai.api-key:}")
  private String aiApiKey;

  @Value("${ai.base-url:}")
  private String aiBaseUrl;

  @Value("${ai.model:}")
  private String aiModel;

  @Value("${auth.secret:}")
  private String authSecret;

  public OpsController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping("/api/health")
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "UP");
    payload.put("service", "issue-ops");
    payload.put("time", OffsetDateTime.now().toString());
    return ApiResponse.ok(payload);
  }

  @GetMapping("/api/readiness")
  public ApiResponse<Map<String, Object>> readiness(HttpServletResponse response) {
    DatabaseStatus database = checkDatabase();
    boolean aiConfigured = aiApiKey != null && !aiApiKey.isBlank();
    boolean authSecretReady =
      authSecret != null &&
      authSecret.length() >= 32 &&
      !DEFAULT_AUTH_SECRET.equals(authSecret);

    String status = database.ready()
      ? (aiConfigured && authSecretReady ? "UP" : "DEGRADED")
      : "DOWN";

    if (!database.ready()) {
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
    payload.put("time", OffsetDateTime.now().toString());
    payload.put(
      "database",
      Map.of("status", database.ready() ? "UP" : "DOWN", "message", database.message())
    );
    payload.put(
      "ai",
      Map.of(
        "configured",
        aiConfigured,
        "provider",
        blankToUnknown(aiProvider),
        "model",
        blankToUnknown(aiModel),
        "baseUrlConfigured",
        aiBaseUrl != null && !aiBaseUrl.isBlank()
      )
    );
    payload.put(
      "auth",
      Map.of(
        "secretConfigured",
        authSecret != null && !authSecret.isBlank(),
        "usingDefaultSecret",
        DEFAULT_AUTH_SECRET.equals(authSecret),
        "secretLengthOk",
        authSecret != null && authSecret.length() >= 32
      )
    );
    return ApiResponse.ok(payload);
  }

  private DatabaseStatus checkDatabase() {
    try {
      Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
      return new DatabaseStatus(Integer.valueOf(1).equals(value), "ok");
    } catch (Exception e) {
      return new DatabaseStatus(false, e.getClass().getSimpleName());
    }
  }

  private String blankToUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private record DatabaseStatus(boolean ready, String message) {}
}
