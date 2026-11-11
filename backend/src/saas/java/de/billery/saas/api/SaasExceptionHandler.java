package de.billery.saas.api;

import de.billery.saas.identity.IdentityException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the SaaS API's refusals to JSON answers like the core's {@code ApiExceptionHandler}. */
@RestControllerAdvice
@Profile("saas")
public class SaasExceptionHandler {

  @ExceptionHandler(ApiError.class)
  ResponseEntity<Map<String, String>> apiError(ApiError e) {
    return ResponseEntity.status(e.httpStatus()).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(IdentityException.class)
  ResponseEntity<Map<String, String>> identity(IdentityException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
  }
}
