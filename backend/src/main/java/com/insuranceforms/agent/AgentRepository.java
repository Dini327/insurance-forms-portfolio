package com.insuranceforms.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    // citext's case-insensitivity does not apply to JDBC-typed varchar bind
    // parameters (only to untyped literals), so comparison is done explicitly
    // via LOWER() rather than relying on the column type.
    @Query("select a from Agent a where lower(a.email) = lower(:email)")
    Optional<Agent> findByEmail(@Param("email") String email);

    @Query("select case when count(a) > 0 then true else false end from Agent a where lower(a.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    boolean existsByAgentNumber(String agentNumber);
}
