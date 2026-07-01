package com.company.issueops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "issue")
public class Issue {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "issue_no", unique = true, nullable = false)
  private String issueNo;

  @Column(nullable = false)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  private String source;

  @Column(name = "business_scene")
  private String businessScene;

  @Column(name = "issue_type")
  private String issueType;

  @Column(name = "impact_scope")
  private String impactScope;

  @Column(name = "customer_impact", columnDefinition = "text")
  private String customerImpact;

  @Column(name = "reproduce_steps", columnDefinition = "text")
  private String reproduceSteps;

  private String priority;
  private String status;

  @Column(name = "responsible_department")
  private String responsibleDepartment;

  @Column(name = "responsible_person")
  private String responsiblePerson;

  @Column(name = "tapd_url")
  private String tapdUrl;

  @Column(name = "attachment_url")
  private String attachmentUrl;

  @Column(name = "root_cause", columnDefinition = "text")
  private String rootCause;

  @Column(name = "fix_solution", columnDefinition = "text")
  private String fixSolution;

  @Column(name = "verify_result", columnDefinition = "text")
  private String verifyResult;

  @Column(name = "is_reopened")
  private Boolean reopened = false;

  @Column(name = "reopened_reason", columnDefinition = "text")
  private String reopenedReason;

  @Column(name = "expected_finish_time")
  private LocalDateTime expectedFinishTime;

  @Column(name = "actual_finish_time")
  private LocalDateTime actualFinishTime;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  private Boolean deleted = false;

  @OneToMany(
    mappedBy = "issue",
    cascade = CascadeType.ALL,
    orphanRemoval = true,
    fetch = FetchType.EAGER
  )
  @OrderBy("createdAt DESC")
  private List<IssueLog> logs = new ArrayList<>();

  @PrePersist
  void create() {
    createdAt = updatedAt = LocalDateTime.now();
    if (createdBy == null) createdBy = "系统用户";
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
  }
}
