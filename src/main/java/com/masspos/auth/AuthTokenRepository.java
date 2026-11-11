package com.masspos.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<AuthToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * Who is signed in where, oldest sign-in first. The user comes with it: the caller wants the
     * name to put on the screen, and a lazy load per token would be a query per person signed in.
     */
    @Query("select t from AuthToken t join fetch t.user where t.revokedAt is null order by t.createdAt asc")
    List<AuthToken> liveSignIns();
}
