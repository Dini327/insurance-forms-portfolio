package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.RegisterAgentRequest;
import com.insuranceforms.validation.IsraeliIdValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AgentRegistrationService {

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;

    public AgentRegistrationService(AgentRepository agentRepository, PasswordEncoder passwordEncoder) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Agent register(RegisterAgentRequest request) {
        Map<String, String> errors = new HashMap<>();

        if (agentRepository.existsByEmail(request.email())) {
            errors.put("email", "Email is already registered");
        }
        if (agentRepository.existsByAgentNumber(request.agentNumber())) {
            errors.put("agentNumber", "Agent number is already registered");
        }
        if (!IsraeliIdValidator.isValid(request.israeliId())) {
            errors.put("israeliId", "Invalid Israeli ID number");
        }
        if (!errors.isEmpty()) {
            throw new RegistrationException(errors);
        }

        Agent agent = new Agent(
                request.fullName(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.israeliId(),
                request.agencyName(),
                request.mobile(),
                request.agentNumber());
        return agentRepository.save(agent);
    }
}
