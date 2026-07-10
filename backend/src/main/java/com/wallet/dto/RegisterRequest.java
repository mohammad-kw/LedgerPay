package com.wallet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The JSON request body for POST /api/auth/register (PROJECT_SPEC.md
 * Section 5).
 *
 * This is a Java `record` - a special kind of class (since Java 16) whose
 * entire purpose is to be an immutable, transparent carrier of data. Writing
 * `record RegisterRequest(String name, ...)` automatically generates:
 *   - private final fields for name, email, password, phone
 *   - a constructor taking all four, in order
 *   - public accessor methods matching the field names exactly (e.g.
 *     `request.name()`, NOT `request.getName()` - this is the one place
 *     records look different from a normal Lombok-style class)
 *   - equals(), hashCode(), and toString() based on all the fields
 * This is a perfect fit for a DTO: DTOs should just be plain data, with no
 * business logic or mutable state, and records enforce exactly that.
 *
 * Why NOT reuse the User @Entity directly as this request body?
 * See com.wallet.dto's package-info.java for the full reasoning -
 * short version: an incoming request should never be allowed to set
 * fields like `id` or `createdAt` that only the server should control, and
 * this DTO's very shape (no id/createdAt fields exist here at all) makes
 * that mistake structurally impossible rather than relying on us
 * remembering to ignore them.
 *
 * The annotations below are Jakarta Bean Validation constraints
 * (PROJECT_SPEC.md Section 7: "Validate all incoming request bodies").
 * They do nothing by themselves - they are only enforced because the
 * controller method that receives this record is annotated with
 * @Valid (see AuthController). If a constraint fails, Spring
 * automatically responds with a 400 Bad Request before our controller
 * code even runs, and our GlobalExceptionHandler formats that failure into
 * a clean JSON error response.
 */
public record RegisterRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 150, message = "Email must be at most 150 characters")
        String email,

        // max = 72 is not an arbitrary number: BCrypt (used to hash this
        // password - see PasswordEncoderConfig) silently IGNORES any bytes
        // beyond the 72nd byte of input. Accepting a longer password would
        // be misleading - the user would think their full password matters
        // when only the first 72 characters actually get hashed.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
        String password,

        @Size(max = 15, message = "Phone must be at most 15 characters")
        @Pattern(
                regexp = "^$|^[0-9]{10,15}$",
                message = "Phone must be 10 to 15 digits")
        String phone
) {
}
