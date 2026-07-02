package com.company.issueops.config;

import com.company.issueops.common.ApiResponse;
import com.company.issueops.service.AuthService;
import com.company.issueops.service.AuthService.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

  private final AuthService authService;
  private final ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
  ) throws ServletException, IOException {
    String path = request.getRequestURI();
    if (
      !path.startsWith("/api") ||
      path.equals("/api/auth/login") ||
      path.startsWith("/api/auth/sso/")
    ) {
      filterChain.doFilter(request, response);
      return;
    }
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    Optional<AuthUser> user = authService.authenticate(
      request.getHeader("Authorization")
    );
    if (user.isEmpty()) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
      return;
    }
    if (!authService.canAccess(user.get(), request.getMethod(), path)) {
      writeError(response, HttpServletResponse.SC_FORBIDDEN, "当前角色无权执行该操作");
      return;
    }

    request.setAttribute(AuthService.REQUEST_USER_ATTRIBUTE, user.get());
    filterChain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, int status, String message)
    throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/json;charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), new ApiResponse<>(status, message, null));
  }
}
