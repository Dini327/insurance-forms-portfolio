package com.insuranceforms.admin.dto;

import com.insuranceforms.agent.Agent;

import java.time.Instant;
import java.util.UUID;

public record AdminAgentSummary(
        UUID id, String fullName, String agencyName, String email, String mobile,
        String agentNumber, boolean isActive, Instant trialStartedAt
) {
    public static AdminAgentSummary from(Agent agent) {
        return new AdminAgentSummary(
                agent.getId(), agent.getFullName(), agent.getAgencyName(), agent.getEmail(),
                agent.getMobile(), agent.getAgentNumber(), agent.isActive(), agent.getTrialStartedAt());
    }
}
