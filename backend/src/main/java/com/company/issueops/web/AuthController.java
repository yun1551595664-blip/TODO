package com.company.issueops.web;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AuthSession;
import com.company.issueops.service.AuthService.AuthUser;
import com.company.issueops.service.AuthService.SsoConfig;
import com.company.issueops.service.AuthService.SsoLoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

  private final AuthService authService;

  @PostMapping("/login")
  ApiResponse<AuthSession> login(
    @RequestBody Map<String, String> body,
    HttpServletResponse response
  ) {
    try {
      return ApiResponse.ok(
        authService.login(body.get("username"), body.get("password"))
      );
    } catch (IllegalArgumentException e) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return new ApiResponse<>(401, e.getMessage(), null);
    }
  }

  @GetMapping("/me")
  ApiResponse<AuthUser> me(HttpServletRequest request) {
    return ApiResponse.ok(
      (AuthUser) request.getAttribute(AuthService.REQUEST_USER_ATTRIBUTE)
    );
  }

  @GetMapping("/sso/config")
  ApiResponse<SsoConfig> ssoConfig() {
    return ApiResponse.ok(authService.ssoConfig());
  }

  @PostMapping("/sso/login")
  ApiResponse<SsoLoginResponse> ssoLogin(HttpServletResponse response) {
    try {
      return ApiResponse.ok(authService.ssoLogin());
    } catch (IllegalStateException e) {
      response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
      return new ApiResponse<>(501, e.getMessage(), null);
    }
  }
}
