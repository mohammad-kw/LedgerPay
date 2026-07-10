package com.wallet.dto;

/**
 * The JSON response body returned by both POST /api/auth/login and
 * POST /api/auth/refresh (PROJECT_SPEC.md Section 5) - both endpoints
 * hand back the same shape: a fresh pair of tokens.
 *
 * See RegisterRequest's javadoc for why this is a plain Java `record`.
 *
 * Notice this is a completely different, purpose-built shape from the
 * `User` @Entity - in particular, `passwordHash` never appears anywhere
 * near this class. That's not an accident we have to remember every time;
 * it's structurally impossible to leak it because this record simply has
 * no field for it.
 */
public record AuthResponse(

        /** A short-lived (15 min default) signed JWT. Sent by the client on every subsequent request as "Authorization: Bearer {accessToken}". */
        String accessToken,

        /** A longer-lived (7 day default), revocable opaque token (see RefreshToken.java). Sent ONLY to POST /api/auth/refresh when the access token has expired, to obtain a new pair without forcing the user to log in again. */
        String refreshToken,

        /** How many seconds from now the accessToken expires. Lets the frontend proactively refresh shortly before expiry instead of waiting for a request to fail first. */
        long expiresInSeconds
) {
}
