package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AccountMutation;
import com.company.issueops.service.AuthService.AccountView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AccountController {

  private final AuthService authService;

  @GetMapping
  ApiResponse<List<AccountView>> list() {
    return ApiResponse.ok(authService.listAccounts());
  }

  @PostMapping
  ApiResponse<AccountView> create(@Valid @RequestBody AccountRequest request) {
    return ApiResponse.ok(authService.createAccount(request.toMutation()));
  }

  @PutMapping("/{id}")
  ApiResponse<AccountView> update(
    @PathVariable Long id,
    @RequestBody AccountRequest request
  ) {
    return ApiResponse.ok(authService.updateAccount(id, request.toMutation()));
  }

  @PatchMapping("/{id}/enabled")
  ApiResponse<AccountView> enabled(
    @PathVariable Long id,
    @RequestBody Map<String, Boolean> body
  ) {
    return ApiResponse.ok(authService.enabled(id, Boolean.TRUE.equals(body.get("enabled"))));
  }

  public record AccountRequest(
    String username,
    String password,
    @NotBlank(message = "显示名称不能为空") String displayName,
    @NotBlank(message = "角色不能为空") String role,
    Boolean enabled,
    String ssoSubject
  ) {
    AccountMutation toMutation() {
      return new AccountMutation(
        username,
        password,
        displayName,
        role,
        enabled,
        ssoSubject
      );
    }
  }
}
