package com.insuranceforms.agent;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AgentAccountIsolationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void bodyIdIsIgnoredAndOnlyTheSessionAgentIsUpdated() {
        Agent agentA = agentRepository.save(new Agent("Agent A", "isoa@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-5001"));
        Agent agentB = agentRepository.save(new Agent("Agent B", "isob@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency B", "0502222222", "AG-5002"));

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "isoa@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        HttpHeaders patchHeaders = new HttpHeaders();
        patchHeaders.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        patchHeaders.add("X-XSRF-TOKEN", csrfToken);
        Map<String, Object> spoofedPayload = new HashMap<>();
        spoofedPayload.put("id", agentB.getId().toString());
        spoofedPayload.put("fullName", "Agent A Updated");
        spoofedPayload.put("agencyName", "Agency A Updated");
        spoofedPayload.put("mobile", "0501111199");

        ResponseEntity<Map> patchResponse = restTemplate.exchange("/api/agent/me", HttpMethod.PATCH,
                new HttpEntity<>(spoofedPayload, patchHeaders), Map.class);

        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResponse.getBody().get("id")).isEqualTo(agentA.getId().toString());

        Agent reloadedA = agentRepository.findById(agentA.getId()).orElseThrow();
        Agent reloadedB = agentRepository.findById(agentB.getId()).orElseThrow();
        assertThat(reloadedA.getFullName()).isEqualTo("Agent A Updated");
        assertThat(reloadedB.getFullName()).isEqualTo("Agent B");
        assertThat(reloadedB.getAgencyName()).isEqualTo("Agency B");
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String extractCookie(ResponseEntity<?> response, String name) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> c.split(name + "=")[1].split(";")[0])
                .orElseThrow(() -> new IllegalStateException(name + " cookie not found in response"));
    }
}
