/**
 * Global exception handling: {@link com.wallet.exception.GlobalExceptionHandler}
 * is annotated @RestControllerAdvice with @ExceptionHandler methods that
 * catch exceptions thrown anywhere in the controller/service layers (e.g.
 * "duplicate email", "invalid refresh token", "insufficient balance" in a
 * later phase, Bean Validation failures) and turn them into consistent,
 * well-structured JSON error responses ({@link com.wallet.exception.ErrorResponse})
 * instead of raw stack traces leaking to API clients.
 *
 * Custom exception classes specific to this domain also live in this
 * package: {@link com.wallet.exception.DuplicateEmailException} and
 * {@link com.wallet.exception.InvalidRefreshTokenException} as of this
 * phase, with more (e.g. InsufficientBalanceException,
 * InvalidTransactionStateException) added alongside the features that can
 * throw them in later phases.
 */
package com.wallet.exception;

