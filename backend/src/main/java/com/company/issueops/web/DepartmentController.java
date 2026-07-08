package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.DepartmentService;
import com.company.issueops.service.DepartmentService.DepartmentView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DepartmentController {

  private final DepartmentService service;

  @GetMapping
  ApiResponse<List<DepartmentView>> list(
    @RequestParam(defaultValue = "false") boolean enabledOnly
  ) {
    return ApiResponse.ok(service.listDepartments(enabledOnly));
  }

  @PostMapping("/sync")
  ApiResponse<List<DepartmentView>> sync(@RequestBody DepartmentSyncRequest request) {
    return ApiResponse.ok(service.sync(request));
  }

  @PostMapping
  ApiResponse<DepartmentView> create(@RequestBody DepartmentRequest request) {
    return ApiResponse.ok(service.create(request));
  }

  @PutMapping("/{id}")
  ApiResponse<DepartmentView> update(
    @PathVariable Long id,
    @RequestBody DepartmentRequest request
  ) {
    return ApiResponse.ok(service.update(id, request));
  }

  @PatchMapping("/{id}/enabled")
  ApiResponse<DepartmentView> enabled(
    @PathVariable Long id,
    @RequestBody EnabledRequest request
  ) {
    return ApiResponse.ok(service.setEnabled(id, request.enabled()));
  }

  @DeleteMapping("/{id}")
  ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.ok(null);
  }

  record EnabledRequest(boolean enabled) {}
}
