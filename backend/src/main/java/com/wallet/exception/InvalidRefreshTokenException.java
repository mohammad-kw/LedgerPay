package com.wallet.exception;

/**
 * Thrown by AuthService.refresh(...) when the provided refresh token
 * string doesn't exist in our `refresh_tokens` table at all, OR it exists
 * but has expired, OR it exists but was already revoked (see
 * RefreshToken.java's javadoc for what "revoked" means and why).
 *
 * We deliberately use this SAME exception for all three of those distinct
 * underlying reasons, rather than three different exception types. This
 * is an intentional security choice, not an oversight: telling a client
 * "this token doesn't exist" vs "this token is expired" vs "this token
 * was revoked" leaks internal state that isn't useful to a legitimate
 * client (a legitimate client's only correct response to ANY of these is
 * identical: discard the token and force the user to log in again) but
 * could be useful to an attacker probing the system. One exception type,
 * one generic client-facing message, mapped by GlobalExceptionHandler to
 * 401 Unauthorized.
 *
 * See DuplicateEmailException's javadoc for why this extends
 * RuntimeException rather than a checked Exception.
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
