package com.insuranceforms.agent;

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
class AgentRepositoryTest {

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void savesAndFindsByEmailCaseInsensitively() {
        Agent agent = new Agent("Ruth Cohen", "Ruth@Example.com", "hash", "123456782",
                "Cohen Insurance", "0501234567", "AG-1001");
        agentRepository.save(agent);

        assertThat(agentRepository.findByEmail("ruth@example.com")).isPresent();
        assertThat(agentRepository.existsByEmail("RUTH@EXAMPLE.COM")).isTrue();
    }

    @Test
    void existsByAgentNumberDetectsDuplicates() {
        agentRepository.save(new Agent("Ruth Cohen", "ruth@example.com", "hash", "123456782",
                "Cohen Insurance", "0501234567", "AG-1002"));

        assertThat(agentRepository.existsByAgentNumber("AG-1002")).isTrue();
        assertThat(agentRepository.existsByAgentNumber("AG-9999")).isFalse();
    }
}
