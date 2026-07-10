package com.wallet.dto;

/**
 * The JSON response body for POST /api/auth/register (PROJECT_SPEC.md
 * Section 5). See RegisterRequest's javadoc for why this is a plain Java
 * `record`.
 *
 * Design choice worth being able to explain in an interview: registration
 * does NOT return access/refresh tokens (unlike login). We deliberately
 * keep "create an account" and "establish a session" as two separate
 * steps, matching the spec's own endpoint list exactly (register creates
 * the user + wallet; login is what returns tokens). Some real-world apps
 * do auto-login immediately after registration for a smoother UX - that
 * would be a simple, explicit addition here later (just call the same
 * token-issuing code AuthService.login() uses), but is not required by
 * the spec, so we keep this phase's scope minimal and unambiguous.
 */
public record RegisterResponse(
        Long userId,
        String name,
        String email
) {
}
