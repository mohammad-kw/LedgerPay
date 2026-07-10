package com.wallet.exception;

/**
 * Thrown by AuthService.register(...) when the email in a RegisterRequest
 * already belongs to an existing account (users.email is UNIQUE per
 * PROJECT_SPEC.md Section 4).
 *
 * Why a custom exception class instead of just throwing a generic
 * RuntimeException with a message? Two concrete reasons:
 *   1. GlobalExceptionHandler (see below) can catch THIS SPECIFIC type and
 *      map it to exactly the right HTTP status (409 Conflict - "the
 *      request conflicts with the current state of the resource", which
 *      is the correct status for "this email is already taken").
 *      A generic RuntimeException would force us to guess/parse a message
 *      string to figure out what actually went wrong.
 *   2. It documents, right in the class name and in code that calls it,
 *      exactly what condition it represents - readable without needing to
 *      chase down where and why it might be thrown.
 *
 * extends RuntimeException (not Exception) so this is an UNCHECKED
 * exception - callers are not forced to declare `throws` or wrap every
 * call in try/catch. This is the conventional choice in Spring
 * applications: checked exceptions add ceremony without much benefit when
 * the normal recovery strategy is "let it propagate up to a global
 * handler", which is exactly our approach here.
 */
public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String message) {
        super(message);
    }
}
