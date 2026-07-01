package com.company.issueops.common;

import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(
    GlobalExceptionHandler.class
  );

  @ExceptionHandler(NoSuchElementException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiResponse<Void> notFound(Exception e) {
    return new ApiResponse<>(404, e.getMessage(), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiResponse<Void> invalid(MethodArgumentNotValidException e) {
    return new ApiResponse<>(
      400,
      e.getBindingResult().getFieldErrors().getFirst().getDefaultMessage(),
      null
    );
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiResponse<Void> illegalArgument(IllegalArgumentException e) {
    return new ApiResponse<>(400, e.getMessage(), null);
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  ApiResponse<Void> error(Exception e) {
    log.error("Unhandled request error", e);
    return new ApiResponse<>(500, e.getMessage(), null);
  }
}
