package com.company.issueops.service;

import java.util.Map;
import java.util.Optional;

public interface AiClient {
  boolean available();

  String provider();

  String model();

  Optional<Map<String, Object>> chatJson(String systemPrompt, String userPrompt);

  Optional<String> chatText(String systemPrompt, String userPrompt);

  Optional<String> chatStream(
    String systemPrompt,
    String userPrompt,
    AiStreamHandler handler
  );

  default AiFailure lastFailure() {
    return AiFailure.none();
  }
}
