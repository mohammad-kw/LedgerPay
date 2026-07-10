package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Maps to a "refresh_tokens" table.
 *
 * IMPORTANT: this table is NOT part of PROJECT_SPEC.md Section 4's original
 * schema - Section 4 covers the wallet/payment business domain only. This
 * entity is infrastructure needed to satisfy Section 7's security
 * requirement: "JWT access tokens short-lived (e.g., 15 min); refresh
 * tokens longer-lived and REVOCABLE." That single word "revocable" is why
 * this table has to exist. Here's the reasoning, which is worth being able
 * to explain out loud in an interview:
 *
 *   - An access token in this project is a self-contained JWT: it is
 *     signed, carries an expiry claim, and can be verified by just
 *     checking its signature - no database lookup needed. That's what
 *     makes it fast to check on every single API request. But this same
 *     property means it CANNOT be revoked early: once issued, it is valid
 *     until its expiry claim passes, no matter what. That's an acceptable
 *     trade-off ONLY because it is short-lived (15 minutes).
 *   - A refresh token is used far less often (only when the access token
 *     expires), so the performance cost of a database lookup on every use
 *     is negligible. That gives us the opportunity to make it a plain
 *     opaque random string (see the `token` field below - just a UUID, NOT
 *     a JWT) that only means anything by being looked up in THIS table.
 *     Revoking it is then as simple as flipping the `revoked` flag (or
 *     deleting the row) - it takes effect immediately, everywhere, because
 *     every use of a refresh token requires asking this table "is this
 *     still valid?" first.
 *
 * This is a deliberate and common real-world pattern: short-lived stateless
 * JWT for frequent checks, longer-lived stateful/DB-backed opaque token for
 * infrequent, revocable checks.
 *
 * See {@link User} for why this class uses @Getter/@Setter/
 * @NoArgsConstructor/@AllArgsConstructor/@Builder instead of Lombok's
 * @Data, and why @ToString/@EqualsAndHashCode are skipped.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The actual opaque token value handed to the client (e.g.
     * "3fa85f64-5717-4562-b3fc-2c963f66afa6" - just a random UUID string,
     * with no embedded meaning at all, unlike a JWT). `unique = true`
     * means the database itself guarantees no two rows can ever share the
     * same token value.
     */
    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    /**
     * Which user this refresh token belongs to - needed so that once a
     * refresh token is validated, we know whose new access token to issue.
     * fetch = LAZY for the same reason as every other relationship in this
     * project (see Wallet.user's javadoc) - don't pull in the whole User
     * row unless something actually calls getUser().
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The absolute point in time after which this refresh token must be
     * rejected even if it's never explicitly revoked. LocalDateTime is
     * used here (rather than the arguably-more-correct Instant) purely for
     * consistency with every other timestamp field in this codebase, which
     * all use LocalDateTime.
     */
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    /**
     * True once this token has been explicitly invalidated - either
     * because it was used once already (see the "refresh token rotation"
     * explanation on AuthService.refresh()) or because of an explicit
     * logout/security event in a future phase. A revoked token must be
     * rejected even if `expiryDate` hasn't passed yet.
     */
    @Builder.Default
    @Column(name = "revoked", nullable = false)
    private Boolean revoked = false;

    /** When this refresh token was issued. See User.createdAt for why we use Hibernate's @CreationTimestamp instead of a DB-level default. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
