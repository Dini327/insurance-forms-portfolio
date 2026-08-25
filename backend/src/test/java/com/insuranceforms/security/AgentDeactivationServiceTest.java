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
import java.util.UUID;

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
        String sessionCookieBrowserOne = loginAndGetSessionCookie(csrfToken, "revoke@example.com");
        String sessionCookieBrowserTwo = loginAndGetSessionCookie(csrfToken, "revoke@example.com");
        assertThat(sessionCookieBrowserOne).isNotEqualTo(sessionCookieBrowserTwo);

        deactivationService.deactivate(agent.getId());

        assertSessionRejected(sessionCookieBrowserOne);
        assertSessionRejected(sessionCookieBrowserTwo);
    }

    // The other half of the isolation guarantee: deactivating one agent must not touch
    // another agent's session. If findByPrincipalName's scoping were ever loosened (or
    // swapped for something that deletes broadly), this is the only test that would
    // catch it -- the test above only proves agent A's own sessions die, never that a
    // stranger's session survives.
    @Test
    void deactivationDoesNotRevokeAnotherAgentsSessions() {
        Agent agentA = agentRepository.save(new Agent("Agent A", "revoke-a@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-4002"));
        Agent agentB = agentRepository.save(new Agent("Agent B", "revoke-b@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency B", "0502222222", "AG-4003"));

        String csrfToken = fetchCsrfToken();
        String sessionCookieA = loginAndGetSessionCookie(csrfToken, "revoke-a@example.com");
        String sessionCookieB = loginAndGetSessionCookie(csrfToken, "revoke-b@example.com");

        deactivationService.deactivate(agentA.getId());

        assertSessionRejected(sessionCookieA);
        assertSessionStillWorksAs(sessionCookieB, agentB.getId());
    }

    private String loginAndGetSessionCookie(String csrfToken, String email) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), loginHeaders),
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

    private void assertSessionStillWorksAs(String sessionCookie, UUID expectedAgentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id")).isEqualTo(expectedAgentId.toString());
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
