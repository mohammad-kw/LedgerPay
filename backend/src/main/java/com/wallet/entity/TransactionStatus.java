package com.wallet.entity;

/**
 * Backs the "status" VARCHAR(20) column on the "transactions" table
 * (PROJECT_SPEC.md Section 4), and directly represents the transaction
 * state machine described in Section 3.3:
 *
 *     CREATED -> PENDING -> SUCCESS -> REVERSED
 *                        \-> FAILED
 *
 * Legal transitions only (enforced by service-layer code in a later
 * phase - this enum just defines the possible values, it does not itself
 * enforce the rules):
 *   CREATED   -> PENDING             (we've made the row, now waiting on Razorpay/processing)
 *   PENDING   -> SUCCESS             (Razorpay webhook confirms payment / transfer applied)
 *   PENDING   -> FAILED              (Razorpay reports failure, or a validation error occurs)
 *   SUCCESS   -> REVERSED            (a refund/reversal is issued afterwards)
 *
 * Explicitly ILLEGAL, and something a future TransactionStateMachine /
 * service class must guard against:
 *   CREATED -> SUCCESS   (skipping verification entirely - exactly the bug
 *                         PROJECT_SPEC.md Section 3.3 warns against)
 *   FAILED  -> anything  (a failed transaction is terminal)
 *   REVERSED -> anything (a reversed transaction is terminal)
 */
public enum TransactionStatus {
    CREATED,
    PENDING,
    SUCCESS,
    FAILED,
    REVERSED
}
