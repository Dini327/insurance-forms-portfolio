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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Proves eraseCredentials() actually removes the bcrypt hash from what
// spring-session-jdbc persists, by reading back the raw bytea column --
// not by asserting on the code path, since the manually-wired
// JsonAuthenticationFilter has already been shown (see AgentAccountIsolationTest's
// deviation note) to silently skip Spring Security wiring that the formLogin()
// DSL would normally provide for free.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class SessionCredentialErasureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void passwordHashIsNotPersistedInTheStoredSession() {
        String rawPassword = "correct-horse-battery";
        String passwordHash = encoder.encode(rawPassword);
        agentRepository.save(new Agent("Ruth Cohen", "erase@example.com", passwordHash,
                "123456782", "Cohen Insurance", "0501234567", "AG-6001"));

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "erase@example.com", "password", rawPassword), loginHeaders),
                Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        byte[] attributeBytes = jdbcTemplate.queryForObject(
                "select a.attribute_bytes from spring_session_attributes a "
                        + "join spring_session s on s.primary_id = a.session_primary_id "
                        + "where s.principal_name = ? and a.attribute_name = 'SPRING_SECURITY_CONTEXT'",
                byte[].class, "erase@example.com");

        assertThat(attributeBytes).isNotNull();
        String rawContent = new String(attributeBytes, StandardCharsets.ISO_8859_1);
        assertThat(rawContent).doesNotContain(passwordHash);
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }
}
