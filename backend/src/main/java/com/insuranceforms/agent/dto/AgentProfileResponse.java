package com.insuranceforms.agent.dto;

import com.insuranceforms.agent.Agent;

import java.util.UUID;

public record AgentProfileResponse(
        UUID id, String fullName, String email, String agencyName, String mobile,
        String agentNumber, boolean logoUploaded
) {
    public static AgentProfileResponse from(Agent agent) {
        return new AgentProfileResponse(
                agent.getId(), agent.getFullName(), agent.getEmail(), agent.getAgencyName(),
                agent.getMobile(), agent.getAgentNumber(), agent.getLogoId() != null);
    }
}
