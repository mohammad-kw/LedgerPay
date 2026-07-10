package com.wallet.exception;

/**
 * Thrown by TransferService for transfer requests that are semantically
 * invalid in a way the bean-validation annotations on the request DTO cannot
 * express on their own - specifically:
 *   - the sender is trying to send money to themselves (sender == receiver), or
 *   - the named receiver has no account/wallet in our system.
 *
 * These are distinct from InsufficientBalanceException: there, the request was
 * valid but unaffordable (422); here, the request itself doesn't make sense
 * (400 Bad Request). Keeping them as separate exception types lets
 * GlobalExceptionHandler return the semantically correct status for each, and
 * lets the frontend show the right message.
 *
 * NOTE on the "receiver not found" case: we deliberately fold it into this
 * 400 rather than returning a 404. A 404 on "look up user by email" would let
 * an attacker probe which email addresses are registered (an account-
 * enumeration leak). Treating "no such receiver" as just another invalid-
 * transfer input avoids confirming or denying whether a given email exists.
 *
 * extends RuntimeException (unchecked) - same rationale as the rest of this
 * package.
 */
public class InvalidTransferException extends RuntimeException {
    public InvalidTransferException(String message) {
        super(message);
    }
}
