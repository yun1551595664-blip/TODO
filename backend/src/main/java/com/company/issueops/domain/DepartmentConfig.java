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
@Table(name = "department_config")
public class DepartmentConfig {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 60)
  private String code;

  @Column(nullable = false, unique = true, length = 80)
  private String name;

  @Column(name = "parent_code", length = 60)
  private String parentCode;

  @Column(name = "sort_order")
  private Integer sortOrder = 100;

  private Boolean enabled = true;

  @Column(length = 30)
  private String source = "SYSTEM";

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  void create() {
    createdAt = updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (source == null || source.isBlank()) source = "SYSTEM";
    if (sortOrder == null) sortOrder = 100;
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (source == null || source.isBlank()) source = "SYSTEM";
    if (sortOrder == null) sortOrder = 100;
  }
}
