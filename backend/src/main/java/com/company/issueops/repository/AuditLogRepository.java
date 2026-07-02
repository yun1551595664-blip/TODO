package com.company.issueops.repository;

import com.company.issueops.domain.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
  List<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
    String targetType,
    String targetId
  );
}
