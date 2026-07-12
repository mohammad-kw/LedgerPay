package com.wallet.exception;

/**
 * Thrown by the admin user-management endpoints (GET /api/admin/users/{id})
 * when no user exists with the requested id.
 *
 * Unlike TransactionNotFoundException (which hides ownership to prevent IDOR
 * enumeration by ordinary users), this is an ADMIN-only endpoint - the admin
 * is authorized to see every user - so a plain "not found" is fine here.
 *
 * GlobalExceptionHandler maps this to 404 Not Found. See
 * DuplicateEmailException's javadoc for why this extends RuntimeException.
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
