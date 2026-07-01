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
@Table(
  name = "dictionary_item",
  uniqueConstraints = @UniqueConstraint(
    name = "uk_dictionary_type_code",
    columnNames = { "dict_type", "code" }
  )
)
public class DictionaryItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "dict_type", nullable = false, length = 50)
  private String dictType;

  @Column(nullable = false, length = 80)
  private String code;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "sort_order")
  private Integer sortOrder = 0;

  private Boolean enabled = true;

  @Column(name = "system_builtin")
  private Boolean systemBuiltin = false;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  private Boolean deleted = false;

  @PrePersist
  void create() {
    createdAt = updatedAt = LocalDateTime.now();
    if (enabled == null) enabled = true;
    if (systemBuiltin == null) systemBuiltin = false;
    if (deleted == null) deleted = false;
    if (sortOrder == null) sortOrder = 0;
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
  }
}
