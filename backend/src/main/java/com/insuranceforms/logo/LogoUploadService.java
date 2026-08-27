package com.insuranceforms.logo;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LogoUploadService {

    private static final Logger log = LoggerFactory.getLogger(LogoUploadService.class);

    private final AgentRepository agentRepository;
    private final LogoStorage logoStorage;
    private final LogoImageProcessor processor;

    public LogoUploadService(AgentRepository agentRepository, LogoStorage logoStorage, LogoImageProcessor processor) {
        this.agentRepository = agentRepository;
        this.logoStorage = logoStorage;
        this.processor = processor;
    }

    // Order matters: store the new logo and point agent.logoId at it BEFORE deleting
    // the old one. A delete failure is logged, not surfaced as a request failure, so
    // agent.logoId can never end up pointing at a file that was deleted.
    public String replaceLogo(UUID agentId, byte[] uploadedBytes) {
        Agent agent = agentRepository.findById(agentId).orElseThrow();
        byte[] processed = processor.process(uploadedBytes);

        LogoRef newLogo = logoStorage.store(processed);
        UUID oldLogoId = agent.getLogoId();

        agent.setLogoId(newLogo.id());
        agentRepository.save(agent);

        if (oldLogoId != null) {
            try {
                logoStorage.delete(oldLogoId);
            } catch (RuntimeException e) {
                log.error("Failed to delete replaced logo {} for agent {}", oldLogoId, agentId, e);
            }
        }
        return newLogo.url();
    }
}
