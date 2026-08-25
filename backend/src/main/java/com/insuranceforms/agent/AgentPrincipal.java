package com.insuranceforms.agent;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

// Deliberately holds only the fields needed for authentication/authorization —
// never the JPA entity itself. Storing the entity would make its shape part of
// the session's serialized contract: adding an unrelated column to Agent would
// then risk breaking deserialization of every session already persisted by
// spring-session-jdbc across a deploy.
public class AgentPrincipal implements UserDetails, CredentialsContainer, Serializable {

    private static final long serialVersionUID = 1L;

    private final UUID agentId;
    private final String email;
    private String passwordHash;
    private final boolean active;

    public AgentPrincipal(Agent agent) {
        this.agentId = agent.getId();
        this.email = agent.getEmail();
        this.passwordHash = agent.getPasswordHash();
        this.active = agent.isActive();
    }

    public UUID getAgentId() {
        return agentId;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    // Called by ProviderManager right after a successful authentication
    // (eraseCredentialsAfterAuthentication, on by default) — the password
    // hash is only ever needed during that one comparison, never again for
    // the life of the session, so it should not linger in the session store.
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
