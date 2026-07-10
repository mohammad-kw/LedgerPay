package com.wallet.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The JSON request body for POST /api/wallet/transfer (PROJECT_SPEC.md
 * Section 5). See RegisterRequest's javadoc for why this is a Java `record`
 * and how the Jakarta Bean Validation annotations below become a 400 (via
 * @Valid on the controller + GlobalExceptionHandler) when violated.
 *
 * As with top-up, the Idempotency-Key is NOT a field here - it travels as an
 * HTTP HEADER, not in the body. This body carries only WHO to pay and HOW
 * MUCH. WHO is SENDING is never in the body either: it's taken from the
 * authenticated JWT, so a caller can only ever send money FROM their own
 * wallet.
 *
 * We identify the receiver by email (rather than a raw wallet/user id)
 * because email is the human-facing handle in this app and doesn't require
 * the sender to know internal ids. The bean-validation here only checks the
 * email is well-FORMED; whether such a user actually exists is a business
 * check done in TransferService (and deliberately reported without confirming
 * account existence - see InvalidTransferException's javadoc).
 */
public record TransferRequest(

        /**
         * The receiver's account email.
         *   @NotBlank - must be present and non-empty.
         *   @Email    - must be a syntactically valid email address.
         */
        @NotBlank(message = "Receiver email is required")
        @Email(message = "Receiver email must be a valid email address")
        String receiverEmail,

        /**
         * The amount (in rupees, the major unit) to transfer. Same validation
         * rationale as TopUpInitiateRequest.amount:
         *   @NotNull                - must be present.
         *   @DecimalMin("1.00")     - must be at least ₹1; blocks zero and
         *                             negatives (Section 7: amounts positive).
         *   @DecimalMax("100000.00")- sane upper bound against typos.
         *   @Digits(13,2)           - at most 2 decimal places, matching the
         *                             DECIMAL(15,2) money columns (Section 4).
         *
         * BigDecimal (never double) for money - exact-decimal reasoning is on
         * Wallet.balance.
         */
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
        @DecimalMax(value = "100000.00", message = "Amount must be at most 100000.00")
        @Digits(integer = 13, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount
) {
}
