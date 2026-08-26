package com.insuranceforms.admin;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// This class shares its Spring context (and Postgres container) with other
// test classes carrying the identical @SpringBootTest/@Import signature --
// see the "shared test database" rule in the plan's Global Constraints.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AdminAgentControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AdminRepository adminRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanSlate() {
        agentRepository.deleteAll();
        adminRepository.deleteAll();
    }

    @SuppressWarnings("unchecked")
    @Test
    void adminSeesAllAgentsWithDefinedFieldsOnlyNoInsuredData() {
        agentRepository.save(new Agent("Ruth Cohen", "list1@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7001"));
        agentRepository.save(new Agent("Dan Levi", "list2@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Levi Insurance", "0507654321", "AG-7003"));
        adminRepository.save(new Admin("Client Admin", "listadmin@example.com",
                encoder.encode("correct-horse-battery")));

        // Total agent count is read *after* seeding, not hardcoded to 2 -- other tests
        // in this shared Spring context may have already created agents of their own.
        // Comparing the response size against the real count (rather than a fixed
        // number) is what catches a silently-filtered or DB-ignoring endpoint without
        // making this test order-dependent on the rest of the class.
        long totalAgents = agentRepository.count();

        String sessionCookie = loginAsAdmin("listadmin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<List> response = restTemplate.exchange("/api/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).hasSize((int) totalAgents);

        List<String> emails = body.stream().map(entry -> (String) entry.get("email")).toList();
        assertThat(emails).contains("list1@example.com", "list2@example.com");

        Map<String, Object> first = body.stream()
                .filter(entry -> "list1@example.com".equals(entry.get("email")))
                .findFirst()
                .orElseThrow();
        assertThat(first.keySet()).containsExactlyInAnyOrder(
                "id", "fullName", "agencyName", "email", "mobile", "agentNumber", "isActive", "trialStartedAt");
        assertThat(first.keySet()).doesNotContain("passwordHash", "password_hash");
    }

    @Test
    void adminCallingAgentOnlyEndpointGets403() {
        adminRepository.save(new Admin("Client Admin", "boundary-admin@example.com",
                encoder.encode("correct-horse-battery")));
        String sessionCookie = loginAsAdmin("boundary-admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void agentCallingAdminOnlyEndpointGets403() {
        agentRepository.save(new Agent("Ruth Cohen", "boundary-agent@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7002"));
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "boundary-agent@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        // Declared as String, not List: a 403 response body from Spring's default
        // error handling is a JSON *object* (`{"timestamp":...,"status":403,...}`),
        // which Jackson cannot deserialize into a List -- it throws before the
        // assertion below even runs, regardless of whether the 403 is correct. This
        // test only needs the status code, not the body shape.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String loginAsAdmin(String email) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), loginHeaders), Map.class);
        return extractCookie(loginResponse, "SESSION");
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
