package com.wallet.exception;

/**
 * Thrown by GET /api/wallet/transactions/{id} when either:
 *   - no transaction exists with the requested id, OR
 *   - a transaction exists but does NOT belong to the requesting user's
 *     wallet (they are neither its sender nor its receiver).
 *
 * SECURITY NOTE (worth explaining in an interview): we deliberately return
 * the SAME 404 for "doesn't exist" and "exists but isn't yours". Returning a
 * distinct 403 for the second case would leak the fact that a transaction
 * with that id exists at all, letting an attacker probe for valid ids. A
 * uniform 404 reveals nothing - this defends against IDOR-style enumeration.
 *
 * GlobalExceptionHandler maps this to 404 Not Found. See
 * DuplicateEmailException's javadoc for why this extends RuntimeException.
 */
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(String message) {
        super(message);
    }
}
