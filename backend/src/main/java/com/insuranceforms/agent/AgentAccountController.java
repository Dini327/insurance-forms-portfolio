package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.AgentProfileResponse;
import com.insuranceforms.agent.dto.UpdateAgentProfileRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/me")
public class AgentAccountController {

    private final AgentRepository agentRepository;

    public AgentAccountController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public AgentProfileResponse getProfile(@AuthenticationPrincipal AgentPrincipal principal) {
        Agent agent = agentRepository.findById(principal.getAgentId()).orElseThrow();
        return AgentProfileResponse.from(agent);
    }

    @PatchMapping
    public AgentProfileResponse updateProfile(@AuthenticationPrincipal AgentPrincipal principal,
                                               @Valid @RequestBody UpdateAgentProfileRequest request) {
        // The agent id always comes from the authenticated principal — request.id() is
        // intentionally never read, even though the DTO carries it, so a client cannot
        // override which row gets updated.
        Agent agent = agentRepository.findById(principal.getAgentId()).orElseThrow();
        agent.setFullName(request.fullName());
        agent.setAgencyName(request.agencyName());
        agent.setMobile(request.mobile());
        agentRepository.save(agent);
        return AgentProfileResponse.from(agent);
    }
}
