package com.insuranceforms.logo;

import com.insuranceforms.admin.Admin;
import com.insuranceforms.admin.AdminRepository;
import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class LogoControllerTest {

    @LocalServerPort
    private int port;

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

    private byte[] renderSamplePng() throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // A file between the framework's 3MB multipart cap and LogoImageProcessor's 2MB
    // business-rule cap: passes the framework gate, gets buffered into memory, and is
    // rejected once decoding/validation runs. Asserting the body (not just the status)
    // matters -- a bare @ResponseStatus on InvalidLogoException produced 400 with no
    // message at all (Spring Boot's default error body omits exception messages unless
    // server.error.include-message is explicitly enabled), confirmed empirically before
    // GlobalExceptionHandler.handleInvalidLogo was added to put the message in the body.
    @Test
    void fileBetweenTheFrameworkAndValidationLimitsGetsA400WithTheActualMessage() throws IOException {
        agentRepository.save(new Agent("Ruth Cohen", "diag1@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-9001"));
        String sessionCookie = loginAsAgent("diag1@example.com");
        String csrfToken = fetchCsrfTokenWithSession(sessionCookie);

        // 2.5MB of arbitrary bytes -- doesn't need to be a valid image, since it's
        // rejected on size before LogoImageProcessor ever attempts to decode it.
        byte[] bigPayload = new byte[(int) (2.5 * 1024 * 1024)];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bigPayload) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });

        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/logo", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("File exceeds the 2MB upload limit");
    }

    // The core of the replace flow: LogoUploadService points agent.logoId at the new
    // file and deletes the old one (see its own ordering comment), so the old opaque
    // URL must stop resolving and the new one must start -- checked through the real
    // public route with a plain, cookie-less RestTemplate (matching an email client),
    // not by asking LogoStorage directly whether the old file is gone.
    @Test
    void uploadingAndReplacingALogoInvalidatesTheOldUrlAndServesTheNew() throws IOException {
        agentRepository.save(new Agent("Ruth Cohen", "logo1@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7001"));
        String sessionCookie = loginAsAgent("logo1@example.com");

        String firstUrl = upload(sessionCookie, renderSamplePng());
        String secondUrl = upload(sessionCookie, renderSamplePng());

        assertThat(firstUrl).isNotEqualTo(secondUrl);

        // A plain RestTemplate throws on 4xx/5xx by default (unlike TestRestTemplate,
        // used elsewhere in this file, which returns the status instead) -- disabling
        // that lets the 404 assertion below actually run instead of the call itself
        // throwing HttpClientErrorException.NotFound. Confirmed empirically: the first
        // run of this test failed exactly that way, with the exception's own body
        // showing the 404 was real -- the assertion was unreachable, not the behavior
        // wrong.
        RestTemplate plainClient = new RestTemplate();
        plainClient.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        ResponseEntity<byte[]> oldResponse = plainClient.getForEntity(
                "http://localhost:" + port + firstUrl, byte[].class);
        assertThat(oldResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<byte[]> newResponse = plainClient.getForEntity(
                "http://localhost:" + port + secondUrl, byte[].class);
        assertThat(newResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(newResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    // Deliberately a brand-new RestTemplate with no cookie store at all -- not merely
    // an unauthenticated agent session, but a request with zero cookies whatsoever,
    // matching exactly what an email client's image request looks like. This
    // distinction matters: if /logos/** were accidentally pulled inside an
    // authenticated security filter chain, a test using this same suite's shared
    // TestRestTemplate (which carries the developer's own session cookies around)
    // could keep passing by accident, and the mistake would only surface in
    // production when a real email client -- which has no session at all -- tries to
    // load the image.
    @Test
    void publicLogoRouteRequiresNoSessionAtAll() throws IOException {
        agentRepository.save(new Agent("Ruth Cohen", "logo2@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7002"));
        String sessionCookie = loginAsAgent("logo2@example.com");
        String url = upload(sessionCookie, renderSamplePng());

        RestTemplate noCookieClient = new RestTemplate();
        ResponseEntity<byte[]> response = noCookieClient.getForEntity("http://localhost:" + port + url, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    // Different layer from LocalDiskLogoStorageTest's
    // malformedIdentifierCannotBeParsedIntoAUuidToAttemptPathTraversal -- that one
    // calls LogoStorage directly and is really about path-traversal safety. This one
    // goes through the actual HTTP route: @PathVariable UUID id fails Spring's own
    // argument conversion before the controller method body ever runs, and the
    // question this test answers is what status code that produces at the HTTP
    // boundary -- 400 (a malformed request) is the correct answer, not 500 (which
    // would suggest the server choked on it) or a silent fall-through.
    @Test
    void fetchingWithAMalformedIdReturnsA400NotA500() {
        ResponseEntity<String> response = restTemplate.getForEntity("/logos/not-a-valid-uuid", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Sends a valid, freshly-fetched CSRF token but no SESSION cookie at all -- this
    // isolates authentication from CSRF deliberately. A request with neither would
    // trip the CSRF filter first and get 403 (see CsrfTest.postWithoutCsrfCookieIsRejected
    // for that exact case), which would prove nothing about the ROLE_AGENT check this
    // test targets. Getting 401 here, with CSRF satisfied, proves the request passed
    // CSRF and was rejected by SecurityConfig's authenticationEntryPoint specifically
    // (res.sendError(401), not a login-page redirect -- see SecurityConfig.java).
    @Test
    void uploadWithoutASessionGetsA401() throws IOException {
        String csrfToken = fetchCsrfToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(renderSamplePng()) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });

        // String, not Map: an error body from res.sendError(401) isn't guaranteed to be
        // JSON that Jackson can deserialize into a Map -- same reasoning as the 403
        // tests in AdminAgentControllerTest. Only the status matters here.
        ResponseEntity<String> response = restTemplate.exchange("/api/agent/logo", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // Distinct from the 401 test above: here the caller *is* authenticated, just as
    // the wrong role. /api/agent/logo is matched by agentChain's ROLE_AGENT-only rule
    // (see LogoController's Interfaces note in Task 14), so an admin session -- valid,
    // with a real authenticated principal -- must still be rejected. Same
    // role-boundary pattern as AdminAgentControllerTest.adminCallingAgentOnlyEndpointGets403,
    // just for this endpoint specifically.
    @Test
    void uploadAsAdminGetsA403() throws IOException {
        adminRepository.save(new Admin("Client Admin", "logo-admin@example.com",
                encoder.encode("correct-horse-battery")));
        String sessionCookie = loginAsAdmin("logo-admin@example.com");
        String csrfToken = fetchCsrfTokenWithSession(sessionCookie);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(renderSamplePng()) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });

        // String, not Map: a 403 body from Spring's default error handling is a JSON
        // object, not the shape a Map-typed ResponseEntity always expects to deserialize
        // cleanly -- same reasoning as AdminAgentControllerTest's role-boundary tests.
        ResponseEntity<String> response = restTemplate.exchange("/api/agent/logo", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

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

    private String upload(String sessionCookie, byte[] pngBytes) {
        String csrfToken = fetchCsrfTokenWithSession(sessionCookie);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pngBytes) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });

        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/logo", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("logoUrl");
    }

    // A file above the framework's 3MB multipart cap used to be covered here by an
    // end-to-end test through the real HTTP stack. Removed: it was flaky by nature, not
    // by a fixable bug. When the upload exceeds the container's multipart limit, Tomcat
    // detects it WHILE the client is still streaming the body -- what the client
    // observes (a clean 413 response vs. a broken-pipe/connection-reset exception)
    // depends on OS-level socket buffering and exact timing, which a black-box HTTP
    // test cannot pin down deterministically (tried both a default RestTemplate and
    // JdkClientHttpRequestFactory, and reading the body as String instead of Map --
    // still reproduced the race on reruns). See GlobalExceptionHandlerTest for the
    // deterministic replacement: it exercises the exception-to-response mapping
    // directly, without going over the network. Spring's own multipart resolver -- the
    // part that actually throws MaxUploadSizeExceededException when the limit is
    // exceeded -- is framework behavior, verified manually rather than covered by an
    // automated test here; see Task 14's deviation note.
    private String loginAsAgent(String email) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), loginHeaders), Map.class);
        return extractCookie(loginResponse, "SESSION");
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String fetchCsrfTokenWithSession(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Void> bootstrap = restTemplate.exchange("/api/csrf-token", HttpMethod.GET,
                new HttpEntity<>(headers), Void.class);
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
