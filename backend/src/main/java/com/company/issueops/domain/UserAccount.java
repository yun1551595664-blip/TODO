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
@Table(name = "user_account")
public class UserAccount {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 60)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(nullable = false, length = 30)
  private String role;

  @Column(length = 80)
  private String department;

  @Column(name = "data_scope", nullable = false, length = 30)
  private String dataScope = "DEPARTMENT";

  private Boolean enabled = true;

  @Column(name = "sso_subject", length = 160)
  private String ssoSubject;

  @Column(name = "last_login_at")
  private LocalDateTime lastLoginAt;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  void create() {
    createdAt = updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (dataScope == null || dataScope.isBlank()) dataScope = "DEPARTMENT";
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
    if (dataScope == null || dataScope.isBlank()) dataScope = "DEPARTMENT";
  }
}
