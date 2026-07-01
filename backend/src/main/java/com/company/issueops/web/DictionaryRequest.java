package com.company.issueops.web;

import jakarta.validation.constraints.NotBlank;

public record DictionaryRequest(
  @NotBlank(message = "字典类型不能为空") String dictType,
  String code,
  @NotBlank(message = "字典名称不能为空") String name,
  String description,
  Integer sortOrder,
  Boolean enabled
) {}
