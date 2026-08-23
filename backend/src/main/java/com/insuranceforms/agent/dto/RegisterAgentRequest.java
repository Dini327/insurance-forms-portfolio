package com.insuranceforms.agent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterAgentRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, message = "Password must be at least 10 characters") String password,
        @NotBlank String israeliId,
        @NotBlank String agencyName,
        @NotBlank String mobile,
        @NotBlank String agentNumber
) {
}
