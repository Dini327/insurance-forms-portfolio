package com.insuranceforms.web;

import com.insuranceforms.agent.RegistrationException;
import com.insuranceforms.logo.InvalidLogoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<Map<String, Object>> handleRegistration(RegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errors", ex.getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errors", errors));
    }

    // A bare @ResponseStatus on InvalidLogoException would set the status but not the
    // body: Spring Boot's default error body omits the exception message unless
    // server.error.include-message is explicitly enabled -- verified empirically (see
    // Task 14's deviation note), not assumed.
    @ExceptionHandler(InvalidLogoException.class)
    public ResponseEntity<Map<String, String>> handleInvalidLogo(InvalidLogoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    // Same body shape as handleInvalidLogo above, deliberately: both are "your upload
    // was rejected for a size-related reason", just caught at different layers (the
    // servlet container's multipart limit vs. this app's own business-rule limit). A
    // client (the frontend, stage 2) has no reason to special-case which layer caught
    // it; without this handler, Spring's default handling for this exception still
    // returns 413, but with an empty body, forcing exactly that special-casing.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File exceeds the upload size limit"));
    }
}
