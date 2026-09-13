package com.masspos.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<AuthToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
