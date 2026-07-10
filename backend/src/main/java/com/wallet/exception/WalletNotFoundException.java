package com.wallet.exception;

/**
 * Thrown when we look up the current user's wallet and, unexpectedly, don't
 * find one. In normal operation this should be impossible: every user gets
 * a wallet auto-created at registration time (see AuthService.register),
 * so a logged-in user always has one. We still handle the case explicitly
 * rather than letting an Optional.get() blow up with a confusing
 * NoSuchElementException, because "fail with a clear, intentional error"
 * always beats "fail with a vague accidental one" - it makes a genuine data
 * inconsistency (e.g. a wallet row manually deleted) obvious instead of
 * mysterious.
 *
 * GlobalExceptionHandler maps this to 404 Not Found.
 *
 * See DuplicateEmailException's javadoc for why this extends
 * RuntimeException rather than a checked Exception.
 */
public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String message) {
        super(message);
    }
}
