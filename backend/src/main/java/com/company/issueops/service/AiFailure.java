package com.company.issueops.service;

public record AiFailure(String code, String message) {
  public static AiFailure none() {
    return new AiFailure("", "");
  }

  public boolean present() {
    return code != null && !code.isBlank();
  }
}
