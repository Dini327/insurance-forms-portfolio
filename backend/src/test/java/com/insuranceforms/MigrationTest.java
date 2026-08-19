package com.insuranceforms;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void agentTableHasExpectedColumns() {
        var columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'agent'",
                String.class);
        assertThat(columns).contains(
                "id", "full_name", "email", "password_hash", "israeli_id", "agency_name",
                "mobile", "agent_number", "logo_id", "is_active", "trial_started_at",
                "created_at", "updated_at");
    }

    @Test
    void adminTableHasExpectedColumns() {
        var columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'admin'",
                String.class);
        assertThat(columns).contains(
                "id", "full_name", "email", "password_hash", "created_at", "updated_at");
    }

    @Test
    void emailColumnsAreCitext() {
        var agentEmailType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_name = 'agent' and column_name = 'email'",
                String.class);
        var adminEmailType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_name = 'admin' and column_name = 'email'",
                String.class);
        assertThat(agentEmailType).isEqualTo("USER-DEFINED");
        assertThat(adminEmailType).isEqualTo("USER-DEFINED");
    }
}
