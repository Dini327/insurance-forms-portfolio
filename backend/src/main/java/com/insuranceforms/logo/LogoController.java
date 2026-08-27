package com.insuranceforms.logo;

import com.insuranceforms.agent.AgentPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

@RestController
public class LogoController {

    private final LogoUploadService logoUploadService;
    private final LogoStorage logoStorage;

    public LogoController(LogoUploadService logoUploadService, LogoStorage logoStorage) {
        this.logoUploadService = logoUploadService;
        this.logoStorage = logoStorage;
    }

    @PostMapping("/api/agent/logo")
    public Map<String, String> upload(@AuthenticationPrincipal AgentPrincipal principal,
                                       @RequestParam("file") MultipartFile file) {
        try {
            String url = logoUploadService.replaceLogo(principal.getAgentId(), file.getBytes());
            return Map.of("logoUrl", url);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/logos/{id}")
    public ResponseEntity<InputStreamResource> serve(@PathVariable UUID id) {
        InputStreamResource resource = new InputStreamResource(logoStorage.resolve(id));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(resource);
    }
}
