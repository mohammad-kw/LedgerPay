package com.wallet.repository;

import com.wallet.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RefreshToken}. See
 * UserRepository's javadoc for a general explanation of how Spring Data
 * JPA repository interfaces work.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Look up a refresh token row by its opaque token string - this is
     * exactly what happens every time a client calls
     * POST /api/auth/refresh: we take the token string they sent us and
     * look up whether we have a matching, still-valid row for it.
     */
    Optional<RefreshToken> findByToken(String token);

    /**
     * Revoke every refresh token belonging to a given user in a single
     * bulk UPDATE statement, e.g. "log out everywhere" or "I detected this
     * user's refresh token was reused, something is wrong, kill all their
     * sessions" (a common security response to refresh token reuse
     * detection).
     *
     * Why @Modifying + @Query instead of a derived method name?
     * Spring Data method-name-derived queries (like findByEmail above)
     * only work for READ (SELECT) operations. To perform an UPDATE, we
     * must write the JPQL explicitly and mark the method @Modifying so
     * Spring Data knows to run it as an update/delete statement rather
     * than expecting a result set back.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.revoked = false")
    void revokeAllByUserId(@Param("userId") Long userId);
}
