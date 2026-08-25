package com.insuranceforms.security;

import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class CsrfTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void postWithoutCsrfCookieIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/login",
                Map.of("email", "nobody@example.com", "password", "irrelevant-password"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void postWithFetchedCsrfCookieAndHeaderIsAccepted() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("XSRF-TOKEN=");
        String token = setCookie.split("XSRF-TOKEN=")[1].split(";")[0];

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", "nobody@example.com", "password", "irrelevant-password"), headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/auth/agent/login", HttpMethod.POST, request, Map.class);

        // 401 (bad credentials), not 403 — proves the request passed CSRF and reached the auth filter.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
