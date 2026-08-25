package com.insuranceforms.security;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Proves session revocation itself, not the login-time isEnabled() gate: both
// SESSION cookies are captured *before* deactivation and reused afterward. If
// deactivate() only flipped is_active without deleting the stored sessions,
// these follow-up requests would still succeed (200), since isEnabled() is
// only consulted once, during the original login -- not on every request.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentDeactivationServiceTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentDeactivationService deactivationService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void deactivationRevokesEveryOpenSessionForTheAgent() {
        Agent agent = agentRepository.save(new Agent("Ruth Cohen", "revoke@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Cohen Insurance", "0501234567", "AG-4001"));

        String csrfToken = fetchCsrfToken();
        // Two independent logins simulate the agent being signed in from two browsers.
        String sessionCookieBrowserOne = loginAndGetSessionCookie(csrfToken);
        String sessionCookieBrowserTwo = loginAndGetSessionCookie(csrfToken);
        assertThat(sessionCookieBrowserOne).isNotEqualTo(sessionCookieBrowserTwo);

        deactivationService.deactivate(agent.getId());

        assertSessionRejected(sessionCookieBrowserOne);
        assertSessionRejected(sessionCookieBrowserTwo);
    }

    private String loginAndGetSessionCookie(String csrfToken) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "revoke@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return extractCookie(loginResponse, "SESSION");
    }

    private void assertSessionRejected(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
