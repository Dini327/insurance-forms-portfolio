package com.insuranceforms.agent;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
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

// This class shares its Spring context (and Postgres container) with other
// test classes carrying the identical @SpringBootTest/@Import signature --
// see the "shared test database" rule in the plan's Global Constraints.
@SuppressWarnings("unchecked")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentRegistrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void cleanSlate() {
        agentRepository.deleteAll();
    }

    private Map<String, Object> validPayload(String email, String agentNumber) {
        return Map.of(
                "fullName", "Ruth Cohen",
                "email", email,
                "password", "correct-horse-battery",
                "israeliId", "123456782",
                "agencyName", "Cohen Insurance",
                "mobile", "0501234567",
                "agentNumber", agentNumber);
    }

    private ResponseEntity<Map> postRegister(Map<String, Object> payload) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);
        return restTemplate.exchange("/api/auth/agent/register", HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    @Test
    void registersWithValidData() {
        ResponseEntity<Map> response = postRegister(validPayload("ruth1@example.com", "AG-2001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(agentRepository.existsByEmail("ruth1@example.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitively() {
        postRegister(validPayload("dup@example.com", "AG-2002"));

        ResponseEntity<Map> response = postRegister(validPayload("DUP@example.com", "AG-2003"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("email");
    }

    @Test
    void rejectsDuplicateAgentNumber() {
        postRegister(validPayload("first@example.com", "AG-2004"));

        ResponseEntity<Map> response = postRegister(validPayload("second@example.com", "AG-2004"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("agentNumber");
    }

    @Test
    void rejectsInvalidIsraeliIdCheckDigit() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("badid@example.com", "AG-2005"));
        payload.put("israeliId", "123456783");

        ResponseEntity<Map> response = postRegister(payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("israeliId");
    }

    @Test
    void rejectsShortPassword() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("shortpw@example.com", "AG-2006"));
        payload.put("password", "short1");

        ResponseEntity<Map> response = postRegister(payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("password");
    }
}
