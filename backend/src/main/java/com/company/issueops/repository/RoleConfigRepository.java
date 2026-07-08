package com.company.issueops.repository;

import com.company.issueops.domain.RoleConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleConfigRepository extends JpaRepository<RoleConfig, Long> {
  Optional<RoleConfig> findByCode(String code);

  boolean existsByCode(String code);

  List<RoleConfig> findAllByOrderBySortOrderAscIdAsc();

  List<RoleConfig> findByEnabledTrueOrderBySortOrderAscIdAsc();

  long countByDefaultDepartment(String defaultDepartment);
}
