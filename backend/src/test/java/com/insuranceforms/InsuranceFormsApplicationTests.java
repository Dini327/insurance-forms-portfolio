package com.insuranceforms;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class InsuranceFormsApplicationTests {

    @Test
    void contextLoads() {
    }
}
