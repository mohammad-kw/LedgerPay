package com.wallet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The JSON request body for POST /api/auth/login (PROJECT_SPEC.md
 * Section 5). See RegisterRequest's javadoc for a full explanation of
 * why DTOs are plain Java `record`s with Bean Validation annotations.
 *
 * Deliberately much simpler than RegisterRequest: login only ever needs an
 * email + password pair to check against what's already stored - there's
 * nothing else to validate the shape of (the actual "is this email
 * registered, and does this password match?" check is business logic that
 * belongs in AuthService, not a structural validation concern here).
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
}
