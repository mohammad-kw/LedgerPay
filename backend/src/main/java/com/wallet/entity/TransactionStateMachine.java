package com.wallet.entity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the transaction state machine described in PROJECT_SPEC.md
 * Section 3.3. This is one of the four "must understand, will be asked in an
 * interview" concepts, so it lives as its own small, pure, dependency-free
 * class that is easy to reason about and to unit-test in isolation (Phase 4).
 *
 * The legal transitions (and nothing else) are:
 *
 *     CREATED  -> PENDING            (we've made the row; now awaiting Razorpay)
 *     CREATED  -> SUCCESS            (payment.captured arrived directly*)
 *     CREATED  -> FAILED             (payment.failed arrived directly*)
 *     PENDING  -> SUCCESS            (Razorpay confirmed the payment)
 *     PENDING  -> FAILED             (Razorpay reported failure)
 *     SUCCESS  -> REVERSED           (a later refund/reversal)
 *
 *   * Why is CREATED -> SUCCESS allowed here when Section 3.3 explicitly
 *     warns against it? The warning is about skipping VERIFICATION - jumping
 *     to SUCCESS without any trustworthy confirmation. In our top-up flow the
 *     transaction is created as CREATED and the very next thing that can
 *     legitimately move it is a SIGNATURE-VERIFIED webhook. We never issue a
 *     separate PENDING update in between, so the real, verified transition we
 *     perform is CREATED -> SUCCESS. The thing that makes this safe is that it
 *     only ever happens AFTER WebhookService has cryptographically verified
 *     the event - the guarantee Section 3.3 actually cares about. What stays
 *     firmly illegal is any transition OUT of a terminal state.
 *
 * Terminal states (no transition may leave them):
 *     FAILED   -> (nothing)
 *     REVERSED -> (nothing)
 *
 * This class only DECIDES legality; callers (WebhookService, and later the
 * transfer service) ask {@link #assertCanTransition} before changing a
 * transaction's status, so an illegal jump throws loudly instead of silently
 * corrupting financial state.
 */
public final class TransactionStateMachine {

    /**
     * For each status, the exact set of statuses it is allowed to move to.
     * An EnumMap is used (rather than a HashMap) because it's the purpose-
     * built, most efficient Map implementation for enum keys - internally
     * just a small array indexed by the enum's ordinal. Statuses absent from
     * this map, or mapped to an empty set (FAILED, REVERSED), are terminal.
     */
    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED =
            new EnumMap<>(TransactionStatus.class);

    static {
        ALLOWED.put(TransactionStatus.CREATED,
                Set.of(TransactionStatus.PENDING, TransactionStatus.SUCCESS, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.PENDING,
                Set.of(TransactionStatus.SUCCESS, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.SUCCESS,
                Set.of(TransactionStatus.REVERSED));
        // FAILED and REVERSED are terminal: intentionally no entry (treated
        // as "no outgoing transitions allowed").
        ALLOWED.put(TransactionStatus.FAILED, Set.of());
        ALLOWED.put(TransactionStatus.REVERSED, Set.of());
    }

    private TransactionStateMachine() {
        // Utility class - never instantiated. All members are static.
    }

    /**
     * @return true iff moving from {@code from} to {@code to} is a legal
     *         transition in the state machine above. A no-op "transition" to
     *         the same status (from == to) is treated as NOT allowed here, so
     *         callers must only call this when they actually intend to change
     *         state (WebhookService uses this to detect "already in this
     *         state" as a separate, non-error case - see its javadoc).
     */
    public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Guard method: throw if the given transition is illegal, otherwise do
     * nothing. Callers use this right before setting a new status, so that an
     * illegal jump (e.g. FAILED -> SUCCESS, or SUCCESS -> SUCCESS) fails fast
     * with a clear error rather than silently proceeding.
     *
     * @throws IllegalStateTransitionException if from -> to is not permitted
     */
    public static void assertCanTransition(TransactionStatus from, TransactionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
