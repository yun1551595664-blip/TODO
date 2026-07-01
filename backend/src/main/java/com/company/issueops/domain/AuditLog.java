package com.company.issueops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_log")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "operator_name", length = 80)
  private String operatorName;

  @Column(name = "operator_role", length = 40)
  private String operatorRole;

  @Column(name = "action_type", nullable = false, length = 80)
  private String actionType;

  @Column(name = "target_type", nullable = false, length = 40)
  private String targetType;

  @Column(name = "target_id", length = 80)
  private String targetId;

  @Column(name = "target_no", length = 80)
  private String targetNo;

  @Column(nullable = false, length = 30)
  private String source;

  @Column(name = "ai_action_id", length = 80)
  private String aiActionId;

  @Column(name = "before_data", columnDefinition = "TEXT")
  private String beforeData;

  @Column(name = "after_data", columnDefinition = "TEXT")
  private String afterData;

  @Column(length = 80)
  private String ip;

  @Column(name = "user_agent", length = 500)
  private String userAgent;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void create() {
    createdAt = LocalDateTime.now();
  }
}
