package com.company.issueops.repository;

import com.company.issueops.domain.Issue;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface IssueRepository
  extends JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {
  Optional<Issue> findTopByIssueNoStartingWithOrderByIssueNoDesc(String prefix);
}
