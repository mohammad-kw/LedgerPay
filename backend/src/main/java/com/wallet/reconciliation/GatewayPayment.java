package com.wallet.reconciliation;

import java.math.BigDecimal;

/**
 * A single payment record AS THE PAYMENT GATEWAY SEES IT - the "other side" of
 * reconciliation (PROJECT_SPEC.md Section 3.5).
 *
 * This is deliberately a small, gateway-NEUTRAL shape: it captures only the
 * four facts the reconciliation engine actually needs to compare against our
 * local {@code transactions} table, regardless of which gateway produced it.
 * Whether the data came from Razorpay's live Payments API or from the
 * simulated source, it is mapped into THIS record first, so the comparison
 * logic in {@link ReconciliationService} never depends on any Razorpay-
 * specific type or JSON shape. That decoupling is what lets the exact same
 * mismatch-detection code run identically today (simulated) and later (real
 * Razorpay) - the differentiator, made testable without a live account.
 *
 * @param gatewayPaymentId the gateway's own unique id for this payment (for
 *                         Razorpay this is the "pay_..." id we store locally
 *                         as razorpay_payment_id). This is the JOIN KEY on
 *                         which local and gateway records are matched.
 * @param status           the gateway's view of the payment outcome, normalised
 *                         to our small {@link GatewayPaymentStatus} enum
 *                         (CAPTURED / FAILED / REFUNDED) so the engine doesn't
 *                         have to know Razorpay's raw status strings.
 * @param amount           the payment amount in the MAJOR unit (rupees), already
 *                         converted from the gateway's minor unit (paise) if
 *                         needed, so it is directly comparable to our
 *                         Transaction.amount (also rupees).
 * @param currency         ISO currency code, e.g. "INR".
 */
public record GatewayPayment(
        String gatewayPaymentId,
        GatewayPaymentStatus status,
        BigDecimal amount,
        String currency
) {
    /** The gateway's normalised view of a payment's outcome, mapped from the provider's raw status strings. */
    public enum GatewayPaymentStatus {
        /** The gateway confirms money was successfully captured (Razorpay: "captured"). */
        CAPTURED,
        /** The gateway says the payment attempt failed (Razorpay: "failed"). */
        FAILED,
        /** The gateway says the payment was captured but later refunded (Razorpay: "refunded"). */
        REFUNDED
    }
}
