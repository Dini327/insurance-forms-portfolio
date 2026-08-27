package com.insuranceforms.web;

import com.insuranceforms.logo.InvalidLogoException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Plain POJO tests, no Spring context and no network: builds each exception directly
// and calls the handler method, proving the exception-to-response mapping our own code
// owns. Replaces an earlier end-to-end HTTP test for the 413 case that was flaky by
// nature (see Task 14's deviation note) -- what this class cannot prove, and doesn't
// try to, is that Spring's multipart resolver actually throws
// MaxUploadSizeExceededException when the configured limit is exceeded; that's
// framework behavior, verified manually rather than by an automated test.
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void invalidLogoExceptionBecomesA400WithTheMessageInTheBody() {
        ResponseEntity<Map<String, String>> response =
                handler.handleInvalidLogo(new InvalidLogoException("File exceeds the 2MB upload limit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "File exceeds the 2MB upload limit");
    }

    @Test
    void maxUploadSizeExceededExceptionBecomesA413WithAConsistentBodyShape() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(3L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("error", "File exceeds the upload size limit");
    }
}
