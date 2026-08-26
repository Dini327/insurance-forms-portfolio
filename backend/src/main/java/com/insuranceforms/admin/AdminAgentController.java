package com.insuranceforms.admin;

import com.insuranceforms.admin.dto.AdminAgentSummary;
import com.insuranceforms.agent.AgentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/agents")
public class AdminAgentController {

    private final AgentRepository agentRepository;

    public AdminAgentController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public List<AdminAgentSummary> list() {
        // Direct dependency on AgentRepository is safe only because `agent` holds no
        // insured data. Stage 2 must replace this with a dedicated aggregate projection
        // once `submission` exists (see spec's Admin visibility boundary section).
        return agentRepository.findAll().stream().map(AdminAgentSummary::from).toList();
    }
}
