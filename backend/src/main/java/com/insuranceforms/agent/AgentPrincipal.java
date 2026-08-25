package com.insuranceforms.agent;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public class AgentPrincipal implements UserDetails, Serializable {

    private final Agent agent;

    public AgentPrincipal(Agent agent) {
        this.agent = agent;
    }

    public UUID getAgentId() {
        return agent.getId();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    @Override
    public String getPassword() {
        return agent.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return agent.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return agent.isActive();
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
}
