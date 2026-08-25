package com.insuranceforms.security;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AgentDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(AgentDeactivationService.class);

    private final AgentRepository agentRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AgentDeactivationService(AgentRepository agentRepository,
                                     FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void deactivate(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent " + agentId));
        agent.setActive(false);
        agentRepository.save(agent);

        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(agent.getEmail());
        sessions.keySet().forEach(sessionRepository::deleteById);
        log.info("Revoked {} active session(s) for deactivated agent {}", sessions.size(), agentId);
    }
}
