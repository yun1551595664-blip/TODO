package com.company.issueops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiInsightSessionStore {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public Map<String, Object> createSession(String insightId, String title) {
    String sessionId = "AIS-" + UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();
    jdbcTemplate.update(
      """
      insert into ai_insight_session(id, insight_id, title, created_at, updated_at)
      values (?, ?, ?, ?, ?)
      """,
      sessionId,
      text(insightId),
      fallback(text(title), "AI 智能洞察对话"),
      Timestamp.valueOf(now),
      Timestamp.valueOf(now)
    );
    return session(sessionId, text(insightId), fallback(text(title), "AI 智能洞察对话"), now, now);
  }

  public Map<String, Object> ensureSession(String sessionId, String insightId) {
    String normalized = text(sessionId);
    if (!normalized.isBlank()) {
      List<Map<String, Object>> existing = jdbcTemplate.query(
        "select * from ai_insight_session where id = ? limit 1",
        (rs, rowNum) -> sessionFrom(rs),
        normalized
      );
      if (!existing.isEmpty()) return existing.getFirst();
    }
    return createSession(insightId, "AI 智能洞察对话");
  }

  public void addUserMessage(String sessionId, String content) {
    addMessage(sessionId, "user", content, null, null, null);
  }

  public void addAssistantMessage(
    String sessionId,
    String content,
    Map<String, Object> structured,
    String model,
    String generatedBy
  ) {
    addMessage(sessionId, "assistant", content, structured, model, generatedBy);
  }

  public List<Map<String, Object>> messages(String sessionId) {
    return jdbcTemplate.query(
      """
      select id, session_id, role, content, structured_json, model, generated_by, created_at
      from ai_insight_message
      where session_id = ?
      order by id asc
      """,
      (rs, rowNum) -> messageFrom(rs),
      sessionId
    );
  }

  public List<Map<String, Object>> recentMessages(String sessionId, int limit) {
    return jdbcTemplate
      .query(
        """
        select id, session_id, role, content, structured_json, model, generated_by, created_at
        from ai_insight_message
        where session_id = ?
        order by id desc
        limit ?
        """,
        (rs, rowNum) -> messageFrom(rs),
        sessionId,
        Math.max(1, limit)
      )
      .reversed();
  }

  private void addMessage(
    String sessionId,
    String role,
    String content,
    Map<String, Object> structured,
    String model,
    String generatedBy
  ) {
    LocalDateTime now = LocalDateTime.now();
    jdbcTemplate.update(
      """
      insert into ai_insight_message(
        session_id, role, content, structured_json, model, generated_by, created_at
      )
      values (?, ?, ?, ?, ?, ?, ?)
      """,
      sessionId,
      role,
      text(content),
      structured == null ? null : toJson(structured),
      text(model),
      text(generatedBy),
      Timestamp.valueOf(now)
    );
    jdbcTemplate.update(
      "update ai_insight_session set updated_at = ? where id = ?",
      Timestamp.valueOf(now),
      sessionId
    );
  }

  private Map<String, Object> sessionFrom(ResultSet rs) throws java.sql.SQLException {
    LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
    LocalDateTime updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
    return session(
      rs.getString("id"),
      rs.getString("insight_id"),
      rs.getString("title"),
      createdAt,
      updatedAt
    );
  }

  private Map<String, Object> messageFrom(ResultSet rs) throws java.sql.SQLException {
    return Map.of(
      "id",
      rs.getLong("id"),
      "sessionId",
      rs.getString("session_id"),
      "role",
      rs.getString("role"),
      "content",
      fallback(rs.getString("content"), ""),
      "structured",
      fromJson(rs.getString("structured_json")),
      "model",
      fallback(rs.getString("model"), ""),
      "generatedBy",
      fallback(rs.getString("generated_by"), ""),
      "createdAt",
      rs.getTimestamp("created_at").toLocalDateTime().toString()
    );
  }

  private Map<String, Object> session(
    String id,
    String insightId,
    String title,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
  ) {
    return Map.of(
      "sessionId",
      id,
      "insightId",
      fallback(insightId, ""),
      "title",
      fallback(title, "AI 智能洞察对话"),
      "createdAt",
      createdAt.toString(),
      "updatedAt",
      updatedAt.toString()
    );
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fromJson(String value) {
    if (value == null || value.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(value, Map.class);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return "{}";
    }
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
