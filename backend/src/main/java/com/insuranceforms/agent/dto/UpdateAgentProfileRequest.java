package com.insuranceforms.agent.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record UpdateAgentProfileRequest(
        UUID id,
        @NotBlank String fullName,
        @NotBlank String agencyName,
        @NotBlank String mobile
) {
}
