package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON request body for POST /api/auth/refresh (PROJECT_SPEC.md
 * Section 5). See RegisterRequest's javadoc for a full explanation of why
 * DTOs are plain Java `record`s with Bean Validation annotations.
 *
 * Just one field: the opaque refresh token string the client was given at
 * login time (see RefreshToken.java's javadoc for why this is a random
 * opaque string, not a JWT).
 */
public record RefreshRequest(

        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
