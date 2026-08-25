package com.insuranceforms.security;

import com.insuranceforms.admin.Admin;
import com.insuranceforms.admin.AdminRepository;
import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class LoginTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AdminRepository adminRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void agentLogsInWithValidCredentials() {
        agentRepository.save(new Agent("Ruth Cohen", "agent-login@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3001"));

        ResponseEntity<Map> response = postLogin("/api/auth/agent/login",
                "agent-login@example.com", "correct-horse-battery");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("AGENT");
    }

    @Test
    void adminLogsInWithValidCredentials() {
        adminRepository.save(new Admin("Client Admin", "admin-login@example.com", encoder.encode("correct-horse-battery")));

        ResponseEntity<Map> response = postLogin("/api/auth/admin/login",
                "admin-login@example.com", "correct-horse-battery");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("ADMIN");
    }

    @Test
    void agentCredentialsRejectedOnAdminLoginEvenWithSameEmail() {
        String sharedEmail = "shared@example.com";
        agentRepository.save(new Agent("Ruth Cohen", sharedEmail, encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3002"));

        ResponseEntity<Map> response = postLogin("/api/auth/admin/login", sharedEmail, "correct-horse-battery");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deactivatedAgentCannotLogIn() {
        Agent agent = new Agent("Ruth Cohen", "deactivated@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3003");
        agent.setActive(false);
        agentRepository.save(agent);

        ResponseEntity<Map> response = postLogin("/api/auth/agent/login", "deactivated@example.com", "correct-horse-battery");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> postLogin(String url, String email, String password) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("email", email, "password", password), headers);
        return restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }
}
