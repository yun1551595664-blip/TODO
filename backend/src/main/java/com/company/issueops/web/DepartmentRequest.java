package com.company.issueops.web;

public record DepartmentRequest(
  String code,
  String name,
  String parentCode,
  Boolean enabled,
  Integer sortOrder
) {}
