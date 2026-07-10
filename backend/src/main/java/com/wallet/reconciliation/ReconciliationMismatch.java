package com.wallet.reconciliation;

import java.math.BigDecimal;

/**
 * One detected discrepancy between our local {@code transactions} table and
 * the payment gateway's records (PROJECT_SPEC.md Section 3.5). A list of these
 * is serialized to JSON and stored in
 * {@code reconciliation_logs.mismatch_details_json} for drill-down.
 *
 * The {@link MismatchType} says WHAT kind of discrepancy it is; the remaining
 * fields carry enough context to investigate it (which local transaction,
 * which gateway payment, and the conflicting values). Fields that don't apply
 * to a given type are left null (e.g. localStatus is null for a payment that
 * exists ONLY at the gateway and has no local row).
 */
public record ReconciliationMismatch(
        MismatchType type,
        Long localTransactionId,
        String gatewayPaymentId,
        String localStatus,
        String gatewayStatus,
        BigDecimal localAmount,
        BigDecimal gatewayAmount,
        String detail
) {

    /**
     * The categories of mismatch the engine detects. These are exactly the
     * cases called out in the spec plus the ones that naturally fall out of a
     * two-way comparison.
     */
    public enum MismatchType {
        /**
         * The gateway has a captured payment that we have NO local transaction
         * for at all (matched by payment id). Money may have been taken from a
         * customer without our system recording it - the most serious case.
         */
        MISSING_LOCALLY,

        /**
         * We have a local SUCCESS top-up, but the gateway has NO corresponding
         * payment record. We think we got paid; the gateway disagrees. Could
         * indicate a wrongly-credited wallet.
         */
        MISSING_AT_GATEWAY,

        /**
         * Both sides know the payment, but disagree on OUTCOME - e.g. we marked
         * it SUCCESS while the gateway says failed or refunded (or vice-versa).
         */
        STATUS_MISMATCH,

        /**
         * Both sides know the payment and agree it succeeded, but the AMOUNTS
         * differ. Even a tiny difference is a red flag worth surfacing.
         */
        AMOUNT_MISMATCH
    }
}
