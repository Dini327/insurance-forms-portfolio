package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.AgentProfileResponse;
import com.insuranceforms.agent.dto.RegisterAgentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/agent")
public class AgentRegistrationController {

    private final AgentRegistrationService registrationService;

    public AgentRegistrationController(AgentRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<AgentProfileResponse> register(@Valid @RequestBody RegisterAgentRequest request) {
        Agent agent = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentProfileResponse.from(agent));
    }
}
