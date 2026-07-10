package com.wallet.exception;

/**
 * Thrown by TransferService when the sender's wallet does not hold enough
 * balance to cover a requested transfer (PROJECT_SPEC.md Section 7:
 * "Validate all incoming request bodies... amounts must be positive" and the
 * Phase 3 failure-path requirement to handle "insufficient balance").
 *
 * Why its own type (see DuplicateEmailException's javadoc for the general
 * rationale)? So GlobalExceptionHandler can map exactly this condition to a
 * precise HTTP status. We use 422 Unprocessable Entity: the request was
 * syntactically valid and well-formed (unlike a 400), we UNDERSTOOD it
 * perfectly - we just can't carry it out because of the current business
 * state (the money isn't there). That is precisely what 422 means, and it
 * lets the frontend distinguish "you sent something malformed" (400) from
 * "your request was fine but you can't afford it" (422).
 *
 * extends RuntimeException (unchecked) for the same reason as every other
 * exception in this package - let it propagate to the global handler.
 */
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
