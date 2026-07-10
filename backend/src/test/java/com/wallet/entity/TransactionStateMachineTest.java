package com.wallet.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link TransactionStateMachine} (PROJECT_SPEC.md
 * Section 3.3 + Phase 4 "unit tests for... transaction state transitions").
 *
 * This class is deliberately dependency-free and static, so these tests need
 * NO Spring context and NO Mockito - they're the fastest kind of test and run
 * in microseconds. We assert three things:
 *   1. every LEGAL transition is allowed,
 *   2. the specific ILLEGAL jumps the spec warns about are rejected, and
 *   3. terminal states (FAILED, REVERSED) can never be left.
 */
class TransactionStateMachineTest {

    // ---- Legal transitions (the complete allowed set from the spec) --------

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "CREATED, PENDING",
            "CREATED, SUCCESS",   // the "verified path" - legal here (see class javadoc on the SM)
            "CREATED, FAILED",
            "PENDING, SUCCESS",
            "PENDING, FAILED",
            "SUCCESS, REVERSED",
    })
    @DisplayName("legal transitions are permitted")
    void legalTransitionsAreAllowed(TransactionStatus from, TransactionStatus to) {
        assertThat(TransactionStateMachine.canTransition(from, to)).isTrue();
        // assertCanTransition must NOT throw for a legal move.
        assertThatCode(() -> TransactionStateMachine.assertCanTransition(from, to))
                .doesNotThrowAnyException();
    }

    // ---- Illegal transitions the spec explicitly cares about ---------------

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            // Cannot resurrect or re-decide a terminal FAILED transaction.
            "FAILED, SUCCESS",
            "FAILED, PENDING",
            "FAILED, REVERSED",
            // REVERSED is terminal too.
            "REVERSED, SUCCESS",
            "REVERSED, PENDING",
            // Cannot go "backwards".
            "SUCCESS, PENDING",
            "SUCCESS, CREATED",
            "PENDING, CREATED",
            // A REVERSAL may only follow a SUCCESS, never a CREATED/PENDING.
            "CREATED, REVERSED",
            "PENDING, REVERSED",
    })
    @DisplayName("illegal transitions are rejected and throw a clear exception")
    void illegalTransitionsAreRejected(TransactionStatus from, TransactionStatus to) {
        assertThat(TransactionStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> TransactionStateMachine.assertCanTransition(from, to))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    /*
     * A test case you might not think of yourself: SELF-transitions (X -> X).
     *
     * WebhookService relies on canTransition(SUCCESS, SUCCESS) returning FALSE
     * to detect "this transaction is ALREADY successful, so a duplicate
     * captured event must NOT credit the wallet again" (idempotency at the
     * money level). If the state machine ever treated same-state as a legal
     * no-op, that duplicate-protection would silently break. So we lock the
     * behaviour down: EVERY status must report that transitioning to itself is
     * NOT allowed.
     */
    @ParameterizedTest(name = "{0} -> {0} (self) is NOT a legal transition")
    @EnumSource(TransactionStatus.class)
    @DisplayName("no state may transition to itself")
    void selfTransitionsAreNeverAllowed(TransactionStatus status) {
        assertThat(TransactionStateMachine.canTransition(status, status)).isFalse();
    }

    @Test
    @DisplayName("terminal states have no outgoing transitions at all")
    void terminalStatesAreDeadEnds() {
        for (TransactionStatus target : TransactionStatus.values()) {
            assertThat(TransactionStateMachine.canTransition(TransactionStatus.FAILED, target))
                    .as("FAILED -> %s", target).isFalse();
            assertThat(TransactionStateMachine.canTransition(TransactionStatus.REVERSED, target))
                    .as("REVERSED -> %s", target).isFalse();
        }
    }
}
