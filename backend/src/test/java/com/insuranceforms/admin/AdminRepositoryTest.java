package com.insuranceforms.admin;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(PostgresTestContainerConfig.class)
class AdminRepositoryTest {

    @Autowired
    private AdminRepository adminRepository;

    @Test
    void savesAndFindsByEmailCaseInsensitively() {
        adminRepository.save(new Admin("Client Admin", "Owner@Example.com", "hash"));

        assertThat(adminRepository.findByEmail("owner@example.com")).isPresent();
    }
}
