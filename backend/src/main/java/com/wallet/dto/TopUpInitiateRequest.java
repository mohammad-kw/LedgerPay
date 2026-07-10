package com.wallet.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The JSON request body for POST /api/wallet/topup/initiate
 * (PROJECT_SPEC.md Section 5). See RegisterRequest's javadoc for why this
 * is a Java `record` and how the Jakarta Bean Validation annotations below
 * are enforced (via @Valid on the controller method, turned into a 400 by
 * GlobalExceptionHandler on failure).
 *
 * Note the Idempotency-Key is NOT a field here - it travels as an HTTP
 * HEADER (per the spec), not in the JSON body, so the controller reads it
 * separately. This body carries only the amount the user wants to add.
 */
public record TopUpInitiateRequest(

        /**
         * The amount of money (in rupees, the major currency unit) to add to
         * the wallet. Validation, top to bottom:
         *   @NotNull    - the field must be present.
         *   @DecimalMin("1.00") inclusive - must be at least ₹1; blocks zero
         *                 and negative amounts (PROJECT_SPEC.md Section 7:
         *                 "amounts must be positive").
         *   @DecimalMax - a sane upper bound so a typo can't create a
         *                 ₹10-crore test order.
         *   @Digits     - at most 13 integer digits and exactly 2 fraction
         *                 digits, matching the DECIMAL(15,2) money columns in
         *                 the schema (Section 4). This stops values like
         *                 "1.999" that couldn't be stored exactly.
         *
         * BigDecimal (never double) for money, for the exact-decimal reason
         * explained on Wallet.balance.
         */
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
        @DecimalMax(value = "100000.00", message = "Amount must be at most 100000.00")
        @Digits(integer = 13, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount
) {
}
