package com.insuranceforms.admin;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the real Spring-wired ApplicationRunner bean actually ran with the
// configured properties -- bean registration, @Value resolution, and the
// ApplicationRunner interface actually getting invoked at startup. Behavioral
// edge cases (idempotency, blank credentials, log content) live in
// AdminBootstrapRunnerLogicTest instead, using manual construction, because
// this class's context only starts once and can't reliably be reused to test
// "what happens on a second bootstrap attempt."
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AdminBootstrapRunnerTest {

    @DynamicPropertySource
    static void bootstrapCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin-bootstrap-email", () -> "owner@example.com");
        registry.add("app.admin-bootstrap-password", () -> "correct-horse-battery");
    }

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsExactlyOneAdminFromEnvOnRealApplicationStartup() {
        assertThat(adminRepository.count()).isEqualTo(1);
        assertThat(adminRepository.findByEmail("owner@example.com")).isPresent();

        // A row existing doesn't prove it's usable -- a wrong hash, or an email saved
        // with stray whitespace/casing, would still pass the two assertions above and
        // then fail to actually log in. Bootstrap only "works" if login works.
        ResponseEntity<Map> loginResponse = login("owner@example.com", "correct-horse-battery");
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().get("role")).isEqualTo("ADMIN");

        ResponseEntity<Map> wrongPasswordResponse = login("owner@example.com", "not-the-right-password");
        assertThat(wrongPasswordResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> login(String email, String password) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);
        return restTemplate.exchange("/api/auth/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), headers), Map.class);
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }
}
