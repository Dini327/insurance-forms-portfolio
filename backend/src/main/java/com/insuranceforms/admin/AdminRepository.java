package com.insuranceforms.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AdminRepository extends JpaRepository<Admin, UUID> {

    // citext's case-insensitivity does not apply to JDBC-typed varchar bind
    // parameters (only to untyped literals), so comparison is done explicitly
    // via LOWER() rather than relying on the column type.
    @Query("select a from Admin a where lower(a.email) = lower(:email)")
    Optional<Admin> findByEmail(@Param("email") String email);
}
