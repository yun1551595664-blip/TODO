package com.company.issueops.repository;

import com.company.issueops.domain.DepartmentConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentConfigRepository extends JpaRepository<DepartmentConfig, Long> {
  List<DepartmentConfig> findAllByOrderBySortOrderAscNameAsc();

  List<DepartmentConfig> findByEnabledTrueOrderBySortOrderAscNameAsc();

  Optional<DepartmentConfig> findByCode(String code);

  Optional<DepartmentConfig> findByName(String name);

  boolean existsByCode(String code);
}
