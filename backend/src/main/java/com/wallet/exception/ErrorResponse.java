package com.wallet.exception;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The consistent JSON shape returned for EVERY error response from this
 * API, no matter what went wrong. Having one predictable error shape
 * (instead of every different failure returning differently-structured
 * JSON, or worse, a raw Java stack trace) is what makes an API pleasant
 * for a frontend to consume - the frontend can write ONE piece of code
 * that reads `error.message` and displays it, regardless of which
 * endpoint or which failure triggered it.
 *
 * See RegisterRequest's javadoc for why this is a plain Java `record`.
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        /**
         * Populated ONLY for Bean Validation failures (e.g. a blank name,
         * an invalid email format) - a list of individual field-level
         * problems so the frontend can highlight exactly which form
         * fields are wrong, instead of just showing one generic message.
         * Null for every other kind of error.
         */
        List<FieldError> fieldErrors
) {
    /** One specific field's validation failure, e.g. field="email", message="Email must be a valid email address". */
    public record FieldError(String field, String message) {
    }
}
