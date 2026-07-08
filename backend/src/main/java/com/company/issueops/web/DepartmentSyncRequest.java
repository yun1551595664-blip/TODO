package com.company.issueops.web;

import java.util.List;

public record DepartmentSyncRequest(List<DepartmentEntry> departments) {
  public record DepartmentEntry(
    String code,
    String name,
    String parentCode,
    Boolean enabled,
    Integer sortOrder
  ) {}
}
