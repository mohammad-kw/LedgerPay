package com.wallet.entity;

/**
 * Thrown by {@link TransactionStateMachine} when code attempts an illegal
 * transaction status transition (PROJECT_SPEC.md Section 3.3) - for example
 * trying to move a FAILED transaction to SUCCESS, or jumping straight to a
 * state that isn't reachable from the current one.
 *
 * This represents a genuine PROGRAMMING/logic error or an unexpected data
 * condition, not a user input problem - if it ever fires, something in our
 * own flow tried to do something financially illegal, and we want it to blow
 * up loudly (and roll back the surrounding DB transaction) rather than
 * silently corrupt state. It therefore extends RuntimeException (unchecked).
 *
 * It lives in the entity package alongside TransactionStateMachine and
 * TransactionStatus because it's an intrinsic part of that state-machine
 * concept, not a web/exception-handling concern.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(TransactionStatus from, TransactionStatus to) {
        super("Illegal transaction state transition: " + from + " -> " + to);
    }
}
