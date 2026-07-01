package com.company.issueops.repository;

import com.company.issueops.domain.IssueLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueLogRepository extends JpaRepository<IssueLog, Long> {}
