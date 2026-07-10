package com.wallet.reconciliation;

import java.time.LocalDate;
import java.util.List;

/**
 * The "seam" that decouples the reconciliation ENGINE from the payment
 * gateway (PROJECT_SPEC.md Section 3.5).
 *
 * Reconciliation is fundamentally about comparing TWO independent sources of
 * truth: our own {@code transactions} table, and the payment gateway's own
 * records. The valuable, interview-defensible part is the COMPARISON /
 * mismatch-detection logic - not the plumbing that fetches the gateway's data.
 * So we hide "how do we get the gateway's records?" behind this one-method
 * interface.
 *
 * Two implementations exist:
 *   • {@code RazorpayGatewaySource}   - the real one, calling Razorpay's
 *                                       Payments API. Active only when
 *                                       app.reconciliation.gateway-provider =
 *                                       razorpay (requires an activated
 *                                       Razorpay account).
 *   • {@code SimulatedGatewaySource}  - the DEFAULT. Derives a plausible set of
 *                                       gateway records from our own local
 *                                       data, so the full engine runs, is
 *                                       testable, and is demoable WITHOUT any
 *                                       live Razorpay account.
 *
 * {@link ReconciliationService} depends only on THIS interface, so switching
 * providers is a one-line config change with zero engine-code changes. That
 * design (program to an interface, inject the implementation) is exactly what
 * makes the differentiator both real and independently testable.
 */
public interface PaymentGatewayReconciliationSource {

    /**
     * Return every payment the gateway has on record for the given calendar
     * day, mapped into our neutral {@link GatewayPayment} shape.
     *
     * @param date the calendar day to fetch the gateway's payments for
     * @return the gateway's payments for that day (never null; empty if none)
     */
    List<GatewayPayment> fetchPaymentsForDate(LocalDate date);

    /**
     * A short human-readable name for WHICH source produced the data (e.g.
     * "razorpay" or "simulated"). Recorded in the reconciliation log so anyone
     * reading a past run knows what it was compared against - important when a
     * demo run was simulated vs a real run hit the live gateway.
     */
    String providerName();
}
