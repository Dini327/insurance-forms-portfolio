package com.insuranceforms.admin;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

// Behavioral edge cases for AdminBootstrapRunner, each self-contained (own
// adminRepository.deleteAll() where needed) so tests don't depend on execution
// order or on each other's side effects -- unlike AdminBootstrapRunnerTest,
// these construct the runner manually instead of relying on the one real
// startup-time invocation, both to exercise a *second* bootstrap attempt and
// so log capture brackets the exact call being tested (OutputCaptureExtension
// only captures within a test method's window; the real ApplicationRunner
// bean already ran once, during context startup, before any test method's
// capture window opens).
@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapRunnerLogicTest {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void secondBootstrapAttemptWithADifferentPasswordDoesNotOverwriteTheExisting() {
        adminRepository.deleteAll();
        new AdminBootstrapRunner(adminRepository, passwordEncoder,
                "restart@example.com", "original-password-123").run(null);
        assertThat(adminRepository.count()).isEqualTo(1);
        String originalHash = adminRepository.findByEmail("restart@example.com").orElseThrow().getPasswordHash();

        new AdminBootstrapRunner(adminRepository, passwordEncoder,
                "restart@example.com", "a-completely-different-password").run(null);

        assertThat(adminRepository.count()).isEqualTo(1);
        Admin after = adminRepository.findByEmail("restart@example.com").orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches("original-password-123", after.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("a-completely-different-password", after.getPasswordHash())).isFalse();
    }

    @Test
    void blankBootstrapCredentialsNeverCreateAnAdmin(CapturedOutput output) {
        adminRepository.deleteAll();

        new AdminBootstrapRunner(adminRepository, passwordEncoder, "blank1@example.com", "").run(null);
        new AdminBootstrapRunner(adminRepository, passwordEncoder, "blank2@example.com", null).run(null);
        new AdminBootstrapRunner(adminRepository, passwordEncoder, null, "some-password").run(null);

        assertThat(adminRepository.count()).isEqualTo(0);
        assertThat(output).contains("ADMIN_BOOTSTRAP_EMAIL", "ADMIN_BOOTSTRAP_PASSWORD");
    }

    @Test
    void successfulBootstrapNeverLogsThePlaintextPassword(CapturedOutput output) {
        adminRepository.deleteAll();
        String rawPassword = "do-not-print-me-98765";

        new AdminBootstrapRunner(adminRepository, passwordEncoder, "logtest@example.com", rawPassword).run(null);

        assertThat(adminRepository.count()).isEqualTo(1);
        assertThat(output).contains("logtest@example.com");
        assertThat(output.getOut() + output.getErr()).doesNotContain(rawPassword);
    }
}
