package com.company.issueops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
@RequiredArgsConstructor
public class OpenAiCompatibleAiClient implements AiClient {

  private static final Logger log = LoggerFactory.getLogger(
    OpenAiCompatibleAiClient.class
  );

  private final ObjectMapper objectMapper;

  private volatile AiFailure lastFailure = AiFailure.none();

  @Value("${ai.provider:deepseek}")
  private String provider;

  @Value("${ai.api-key:}")
  private String apiKey;

  @Value("${deepseek.api-key:}")
  private String legacyDeepSeekApiKey;

  @Value("${ai.base-url:}")
  private String baseUrl;

  @Value("${deepseek.base-url:https://api.deepseek.com}")
  private String legacyDeepSeekBaseUrl;

  @Value("${ai.model:deepseek-chat}")
  private String model;

  @Value("${deepseek.model:deepseek-chat}")
  private String legacyDeepSeekModel;

  @Value("${ai.timeout-ms:60000}")
  private long timeoutMs;

  @Value("${ai.temperature:0.2}")
  private double temperature;

  @Value("${ai.max-tokens:2000}")
  private int maxTokens;

  @Override
  public boolean available() {
    return !isBlank(resolveApiKey());
  }

  @Override
  public String provider() {
    return isBlank(provider) ? "deepseek" : provider.trim();
  }

  @Override
  public String model() {
    if (!isBlank(model)) return model.trim();
    if (!isBlank(legacyDeepSeekModel)) return legacyDeepSeekModel.trim();
    return "deepseek-chat";
  }

  @Override
  public Optional<Map<String, Object>> chatJson(
    String systemPrompt,
    String userPrompt
  ) {
    return chatText(systemPrompt, userPrompt).flatMap(text -> {
      try {
        lastFailure = AiFailure.none();
        return Optional.of(
          objectMapper.readValue(
            extractJson(text),
            new TypeReference<Map<String, Object>>() {}
          )
        );
      } catch (Exception ignored) {
        lastFailure =
          new AiFailure("invalid_json", "AI 返回内容不是合法 JSON，已切换为本地规则结果");
        log.warn("AI response is not valid JSON: {}", ignored.getMessage());
        return Optional.empty();
      }
    });
  }

  @Override
  public Optional<String> chatText(String systemPrompt, String userPrompt) {
    if (!available()) {
      lastFailure = new AiFailure("missing_api_key", "AI API Key 未配置");
      return Optional.empty();
    }
    try {
      SimpleClientHttpRequestFactory requestFactory =
        new SimpleClientHttpRequestFactory();
      Duration timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
      requestFactory.setConnectTimeout(timeout);
      requestFactory.setReadTimeout(timeout);

      RestClient client = RestClient
        .builder()
        .baseUrl(trimTrailingSlash(resolveBaseUrl()))
        .requestFactory(requestFactory)
        .build();

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model());
      body.put("temperature", temperature);
      if (maxTokens > 0) body.put("max_tokens", maxTokens);
      body.put("stream", false);
      body.put(
        "messages",
        List.of(
          Map.of("role", "system", "content", systemPrompt),
          Map.of("role", "user", "content", userPrompt)
        )
      );

      Map<String, Object> response = client
        .post()
        .uri("/chat/completions")
        .headers(headers -> headers.setBearerAuth(resolveApiKey()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});

      Optional<String> content = extractMessageContent(response);
      if (content.isEmpty()) {
        lastFailure =
          new AiFailure("empty_content", "AI 返回内容为空，已切换为本地规则结果");
        log.warn("AI response does not contain assistant content");
      } else {
        lastFailure = AiFailure.none();
      }
      return content;
    } catch (RestClientResponseException e) {
      lastFailure =
        new AiFailure(
          "http_" + e.getStatusCode().value(),
          "AI 请求失败（HTTP " + e.getStatusCode().value() + "），已切换为本地规则结果"
        );
      log.warn(
        "AI request failed: status={}, body={}",
        e.getStatusCode().value(),
        safePreview(e.getResponseBodyAsString())
      );
      return Optional.empty();
    } catch (Exception e) {
      Throwable root = rootCause(e);
      String code =
        root instanceof java.net.SocketTimeoutException ||
        root instanceof java.net.http.HttpTimeoutException
          ? "timeout"
          : "network_error";
      String message = "timeout".equals(code)
        ? "AI 请求超时，已切换为本地规则结果"
        : "AI 请求异常，已切换为本地规则结果";
      lastFailure = new AiFailure(code, message);
      log.warn(
        "AI request failed: {}: {}; rootCause={}: {}",
        e.getClass().getSimpleName(),
        e.getMessage(),
        root.getClass().getSimpleName(),
        root.getMessage()
      );
      return Optional.empty();
    }
  }

  @Override
  public Optional<String> chatStream(
    String systemPrompt,
    String userPrompt,
    AiStreamHandler handler
  ) {
    if (!available()) {
      lastFailure = new AiFailure("missing_api_key", "AI API Key 未配置");
      return Optional.empty();
    }
    StringBuilder result = new StringBuilder();
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model());
      body.put("temperature", temperature);
      if (maxTokens > 0) body.put("max_tokens", maxTokens);
      body.put("stream", true);
      body.put(
        "messages",
        List.of(
          Map.of("role", "system", "content", systemPrompt),
          Map.of("role", "user", "content", userPrompt)
        )
      );

      HttpClient client = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
        .build();
      HttpRequest request = HttpRequest
        .newBuilder()
        .uri(URI.create(trimTrailingSlash(resolveBaseUrl()) + "/chat/completions"))
        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
        .header("Authorization", "Bearer " + resolveApiKey())
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();

      HttpResponse<InputStream> response = client.send(
        request,
        HttpResponse.BodyHandlers.ofInputStream()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        lastFailure =
          new AiFailure(
            "http_" + response.statusCode(),
            "AI 流式请求失败（HTTP " + response.statusCode() + "）"
          );
        log.warn("AI stream request failed: status={}", response.statusCode());
        return Optional.empty();
      }

      try (
        BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body(), StandardCharsets.UTF_8)
        )
      ) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) continue;
          String payload = line.substring(5).trim();
          if (payload.isBlank()) continue;
          if ("[DONE]".equals(payload)) break;
          String delta = extractStreamDelta(payload);
          if (delta.isBlank()) continue;
          result.append(delta);
          handler.onDelta(delta);
        }
      }
      lastFailure = result.length() == 0
        ? new AiFailure("empty_content", "AI 流式返回内容为空")
        : AiFailure.none();
      return result.length() == 0 ? Optional.empty() : Optional.of(result.toString());
    } catch (Exception e) {
      Throwable root = rootCause(e);
      String code =
        root instanceof java.net.SocketTimeoutException ||
        root instanceof java.net.http.HttpTimeoutException
          ? "timeout"
          : "network_error";
      lastFailure =
        new AiFailure(
          code,
          "timeout".equals(code) ? "AI 流式请求超时" : "AI 流式请求异常"
        );
      log.warn(
        "AI stream request failed: {}: {}; rootCause={}: {}",
        e.getClass().getSimpleName(),
        e.getMessage(),
        root.getClass().getSimpleName(),
        root.getMessage()
      );
      return result.length() == 0 ? Optional.empty() : Optional.of(result.toString());
    }
  }

  @Override
  public AiFailure lastFailure() {
    return lastFailure;
  }

  private Optional<String> extractMessageContent(Map<String, Object> response) {
    Object choices = response == null ? null : response.get("choices");
    if (!(choices instanceof List<?> list) || list.isEmpty()) return Optional.empty();
    Object first = list.get(0);
    if (!(first instanceof Map<?, ?> choice)) return Optional.empty();
    Object message = choice.get("message");
    if (!(message instanceof Map<?, ?> messageMap)) return Optional.empty();
    Object content = messageMap.get("content");
    if (!(content instanceof String text) || text.isBlank()) return Optional.empty();
    return Optional.of(text.trim());
  }

  private String extractStreamDelta(String payload) {
    try {
      Map<String, Object> parsed = objectMapper.readValue(
        payload,
        new TypeReference<Map<String, Object>>() {}
      );
      Object choices = parsed.get("choices");
      if (!(choices instanceof List<?> list) || list.isEmpty()) return "";
      Object first = list.get(0);
      if (!(first instanceof Map<?, ?> choice)) return "";
      Object delta = choice.get("delta");
      if (!(delta instanceof Map<?, ?> deltaMap)) return "";
      Object content = deltaMap.get("content");
      return content instanceof String text ? text : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private String extractJson(String text) {
    String normalized = text.trim();
    if (normalized.startsWith("```")) {
      normalized = normalized
        .replaceFirst("^```(?:json)?", "")
        .replaceFirst("```$", "")
        .trim();
    }
    int start = normalized.indexOf('{');
    int end = normalized.lastIndexOf('}');
    if (start >= 0 && end > start) return normalized.substring(start, end + 1);
    return normalized;
  }

  private String resolveBaseUrl() {
    if (!isBlank(baseUrl)) return baseUrl;
    if (!isBlank(legacyDeepSeekBaseUrl)) return legacyDeepSeekBaseUrl;
    return "openai".equalsIgnoreCase(provider())
      ? "https://api.openai.com/v1"
      : "https://api.deepseek.com";
  }

  private String resolveApiKey() {
    if (!isBlank(apiKey)) return apiKey.trim();
    if (!isBlank(legacyDeepSeekApiKey)) return legacyDeepSeekApiKey.trim();
    return "";
  }

  private String trimTrailingSlash(String value) {
    return value.replaceAll("/+$", "");
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private String safePreview(String value) {
    if (value == null || value.isBlank()) return "";
    String compact = value.replaceAll("\\s+", " ").trim();
    return compact.length() > 300 ? compact.substring(0, 300) + "..." : compact;
  }

  private Throwable rootCause(Throwable e) {
    Throwable current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }
}
