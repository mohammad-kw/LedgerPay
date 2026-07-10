package com.wallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and verifies the short-lived, self-contained access token
 * described in PROJECT_SPEC.md Section 2 ("JWT (access token + refresh
 * token)"). This class deliberately knows NOTHING about our User/Wallet
 * entities or the database - it only deals in plain (userId, email)
 * values and raw token strings. That separation keeps it small, easy to
 * unit test in isolation, and reusable no matter what a "user" ends up
 * looking like later.
 *
 * -----------------------------------------------------------------
 * Quick refresher on what a JWT actually is (useful to say out loud in
 * an interview):
 * -----------------------------------------------------------------
 * A JWT is just THREE Base64URL-encoded parts joined by dots:
 *   header.payload.signature
 * - header:    metadata, e.g. which algorithm was used to sign it.
 * - payload:   the "claims" - plain, human-readable JSON key/value data
 *              (who this token is for, when it expires, ...). Anyone can
 *              decode and READ this part without any secret key at all -
 *              a JWT is NOT encrypted, only signed. That's why we must
 *              never put secret data (like a password) in a claim.
 * - signature: HMAC-SHA256(header + "." + payload, ourSecretKey). This is
 *              the part that makes the token trustworthy: if even one
 *              character of the header or payload is tampered with after
 *              signing, re-computing this signature on the receiving end
 *              will produce a different value than what's embedded in the
 *              token, so verification fails and we know the token was
 *              altered or wasn't issued by us.
 *
 * Because signature verification alone is enough to prove "we issued
 * this, and no one has changed it since", we don't need to store access
 * tokens in the database at all or look anything up to check them - that
 * is what makes them fast and "stateless". See RefreshToken.java for why
 * refresh tokens intentionally do NOT work this same way.
 */
@Component
public class JwtService {

    /** The claim name we use to embed our own internal numeric user id alongside the standard "sub" (subject) claim, which we use for the user's email. */
    private static final String CLAIM_USER_ID = "uid";

    /** The claim name carrying the user's role ("USER"/"ADMIN"), so the frontend can route to the admin dashboard without an extra API call. This is a UI convenience only - every request's real authorization is still re-checked server-side against the DB-loaded user, never trusting this claim for access decisions. */
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties jwtProperties;

    /**
     * A javax.crypto.SecretKey wrapping our configured secret's raw bytes -
     * this is the object JJWT's sign/verify methods actually need, rather
     * than a plain String or byte[]. We build it ONCE here in the
     * constructor (instead of on every call to generate/parse a token)
     * since it never changes while the application is running.
     *
     * Keys.hmacShaKeyFor(...) also enforces the JWT specification's
     * minimum key length for us: HMAC-SHA256 requires a key of at least 32
     * bytes. If jwt.secret is ever misconfigured to something too short,
     * this line throws a clear WeakKeyException immediately on startup,
     * rather than allowing us to boot with an insecure key and fail
     * mysteriously later.
     */
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a brand-new, signed access token for the given user.
     *
     * We deliberately put the user's EMAIL in the standard "sub" (subject)
     * claim - the JWT spec's conventional place for "who is this token
     * about" - because that is exactly the value
     * CustomUserDetailsService.loadUserByUsername(String) expects
     * (our User.email doubles as the login username; see User.java).
     * The numeric database id is added as an extra custom claim ("uid")
     * purely as a convenience so callers can get it without a database
     * round-trip, but it is NOT used for authentication lookups.
     */
    public String generateAccessToken(Long userId, String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.accessTokenExpiryMs());

        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses a compact JWT string AND verifies its signature in one step.
     *
     * If the token has been tampered with, was signed with a different
     * key, or is malformed/expired, JJWT throws a subclass of
     * {@link io.jsonwebtoken.JwtException} here (e.g. SignatureException,
     * ExpiredJwtException, MalformedJwtException) - we deliberately let
     * that exception propagate up to the caller (JwtAuthenticationFilter)
     * rather than swallowing it here, since "is this token valid?" and
     * "what do I do about an invalid token?" are different
     * responsibilities that belong in different classes.
     */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Convenience wrapper: parse + validate, then pull out just the "sub" (email) claim. */
    public String extractEmail(String token) {
        return parseAndValidate(token).getSubject();
    }

    /**
     * How many seconds (not milliseconds) an access token lives for, used
     * to populate AuthResponse.expiresInSeconds so the frontend (a later
     * phase) knows when to proactively refresh.
     */
    public long getAccessTokenExpirySeconds() {
        return jwtProperties.accessTokenExpiryMs() / 1000;
    }

    /** How many milliseconds a refresh token should live for - read by AuthService when it creates a new RefreshToken row. */
    public long getRefreshTokenExpiryMs() {
        return jwtProperties.refreshTokenExpiryMs();
    }
}
