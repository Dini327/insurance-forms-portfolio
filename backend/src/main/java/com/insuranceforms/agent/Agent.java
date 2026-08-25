package com.insuranceforms.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent")
public class Agent implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "israeli_id", nullable = false)
    private String israeliId;

    @Column(name = "agency_name", nullable = false)
    private String agencyName;

    @Column(name = "mobile", nullable = false)
    private String mobile;

    @Column(name = "agent_number", nullable = false, unique = true)
    private String agentNumber;

    @Column(name = "logo_id")
    private UUID logoId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "trial_started_at", nullable = false)
    private Instant trialStartedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Agent() {
    }

    public Agent(String fullName, String email, String passwordHash, String israeliId,
                 String agencyName, String mobile, String agentNumber) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.israeliId = israeliId;
        this.agencyName = agencyName;
        this.mobile = mobile;
        this.agentNumber = agentNumber;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getIsraeliId() { return israeliId; }
    public String getAgencyName() { return agencyName; }
    public void setAgencyName(String agencyName) { this.agencyName = agencyName; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getAgentNumber() { return agentNumber; }
    public UUID getLogoId() { return logoId; }
    public void setLogoId(UUID logoId) { this.logoId = logoId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getTrialStartedAt() { return trialStartedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
