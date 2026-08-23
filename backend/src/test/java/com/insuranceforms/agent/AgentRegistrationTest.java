package com.insuranceforms.agent;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentRegistrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

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

    @Test
    void registersWithValidData() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("ruth1@example.com", "AG-2001"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(agentRepository.existsByEmail("ruth1@example.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitively() {
        restTemplate.postForEntity("/api/auth/agent/register", validPayload("dup@example.com", "AG-2002"), Map.class);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("DUP@example.com", "AG-2003"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("email");
    }

    @Test
    void rejectsDuplicateAgentNumber() {
        restTemplate.postForEntity("/api/auth/agent/register", validPayload("first@example.com", "AG-2004"), Map.class);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("second@example.com", "AG-2004"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("agentNumber");
    }

    @Test
    void rejectsInvalidIsraeliIdCheckDigit() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("badid@example.com", "AG-2005"));
        payload.put("israeliId", "123456783");

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/register", payload, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("israeliId");
    }

    @Test
    void rejectsShortPassword() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("shortpw@example.com", "AG-2006"));
        payload.put("password", "short1");

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/register", payload, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("password");
    }
}
