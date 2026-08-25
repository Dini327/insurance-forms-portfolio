package com.insuranceforms.admin;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

// Deliberately holds only the fields needed for authentication/authorization —
// never the JPA entity itself. See AgentPrincipal for the full rationale.
public class AdminPrincipal implements UserDetails, CredentialsContainer, Serializable {

    private static final long serialVersionUID = 1L;

    private final UUID adminId;
    private final String email;
    private String passwordHash;

    public AdminPrincipal(Admin admin) {
        this.adminId = admin.getId();
        this.email = admin.getEmail();
        this.passwordHash = admin.getPasswordHash();
    }

    public UUID getAdminId() {
        return adminId;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
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
        return true;
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

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
