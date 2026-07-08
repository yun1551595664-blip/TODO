package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.RoleService;
import com.company.issueops.service.RoleService.RoleView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RoleController {

  private final RoleService service;

  @GetMapping
  ApiResponse<List<RoleView>> list(
    @RequestParam(defaultValue = "false") boolean enabledOnly
  ) {
    return ApiResponse.ok(service.listRoles(enabledOnly));
  }

  @GetMapping("/permissions")
  ApiResponse<Map<String, String>> permissions() {
    return ApiResponse.ok(service.permissionLabels());
  }

  @PostMapping
  ApiResponse<RoleView> create(@Valid @RequestBody RoleRequest request) {
    return ApiResponse.ok(service.create(request));
  }

  @PutMapping("/{id}")
  ApiResponse<RoleView> update(
    @PathVariable Long id,
    @Valid @RequestBody RoleRequest request
  ) {
    return ApiResponse.ok(service.update(id, request));
  }

  @PatchMapping("/{id}/enabled")
  ApiResponse<RoleView> enabled(
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
}
