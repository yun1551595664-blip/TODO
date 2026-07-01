package com.company.issueops.config;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StatusDataMigration implements ApplicationRunner {

  private final EntityManager entityManager;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    entityManager
      .createNativeQuery(
        "update issue set issue_no = concat('PBI', substring(issue_no, 4)) where issue_no like 'ISS-%'"
      )
      .executeUpdate();
    entityManager
      .createQuery(
        "update Issue i set i.status = '已完成' where i.status in ('已修复', '已关闭')"
      )
      .executeUpdate();
    entityManager
      .createQuery(
        "update Issue i set i.status = '处理中', i.reopened = true where i.status = '已复发'"
      )
      .executeUpdate();
    entityManager
      .createQuery(
        "update Issue i set i.status = '待处理' where i.status = '已挂起'"
      )
      .executeUpdate();
  }
}
