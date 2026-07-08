package com.company.issueops.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RoleRequest(
  String code,
  @NotBlank(message = "角色名称不能为空") String name,
  String description,
  List<String> permissions,
  String defaultDataScope,
  String defaultDepartment,
  Boolean enabled,
  Integer sortOrder
) {}
