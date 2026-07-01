package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.domain.DictionaryItem;
import com.company.issueops.service.DictionaryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dictionaries")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DictionaryController {

  private final DictionaryService service;

  @GetMapping
  ApiResponse<List<DictionaryItem>> list(
    @RequestParam("type") String type,
    @RequestParam(defaultValue = "false") boolean enabledOnly
  ) {
    return ApiResponse.ok(service.list(type, enabledOnly));
  }

  @GetMapping("/grouped")
  ApiResponse<Map<String, List<DictionaryItem>>> grouped(
    @RequestParam(defaultValue = "false") boolean enabledOnly
  ) {
    return ApiResponse.ok(service.grouped(enabledOnly));
  }

  @PostMapping
  ApiResponse<DictionaryItem> create(@Valid @RequestBody DictionaryRequest request) {
    return ApiResponse.ok(service.create(request));
  }

  @PutMapping("/{id}")
  ApiResponse<DictionaryItem> update(
    @PathVariable Long id,
    @Valid @RequestBody DictionaryRequest request
  ) {
    return ApiResponse.ok(service.update(id, request));
  }

  @PatchMapping("/{id}/enabled")
  ApiResponse<DictionaryItem> enabled(
    @PathVariable Long id,
    @RequestBody Map<String, Boolean> body
  ) {
    return ApiResponse.ok(service.enabled(id, Boolean.TRUE.equals(body.get("enabled"))));
  }

  @DeleteMapping("/{id}")
  ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.ok();
  }

  @GetMapping("/{id}/usage")
  ApiResponse<Map<String, Object>> usage(@PathVariable Long id) {
    return ApiResponse.ok(service.usage(id));
  }
}
