package com.insuranceforms.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrapRunner(AdminRepository adminRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.admin-bootstrap-email}") String bootstrapEmail,
                                 @Value("${app.admin-bootstrap-password}") String bootstrapPassword) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminRepository.count() > 0) {
            return;
        }
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            log.warn("No admin account exists and ADMIN_BOOTSTRAP_EMAIL/ADMIN_BOOTSTRAP_PASSWORD "
                    + "are not both set -- the admin panel will be unreachable until an admin is bootstrapped.");
            return;
        }
        String passwordHash = passwordEncoder.encode(bootstrapPassword);
        adminRepository.save(new Admin("Platform Admin", bootstrapEmail, passwordHash));
        log.info("Bootstrapped admin account {}", bootstrapEmail);
    }
}
