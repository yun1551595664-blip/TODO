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
@Table(name = "role_config")
public class RoleConfig {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 30)
  private String code;

  @Column(nullable = false, length = 60)
  private String name;

  @Column(length = 255)
  private String description;

  @Column(nullable = false, length = 500)
  private String permissions = "";

  @Column(name = "default_data_scope", nullable = false, length = 30)
  private String defaultDataScope = "DEPARTMENT";

  @Column(name = "default_department", length = 80)
  private String defaultDepartment;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(nullable = false)
  private Boolean enabled = true;

  @Column(name = "system_builtin", nullable = false)
  private Boolean systemBuiltin = false;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  void create() {
    createdAt = updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (systemBuiltin == null) systemBuiltin = false;
    if (permissions == null) permissions = "";
    if (sortOrder == null) sortOrder = 0;
    if (defaultDataScope == null || defaultDataScope.isBlank()) defaultDataScope = "DEPARTMENT";
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (systemBuiltin == null) systemBuiltin = false;
    if (permissions == null) permissions = "";
    if (sortOrder == null) sortOrder = 0;
    if (defaultDataScope == null || defaultDataScope.isBlank()) defaultDataScope = "DEPARTMENT";
  }
}
