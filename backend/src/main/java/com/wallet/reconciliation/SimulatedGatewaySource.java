package com.wallet.reconciliation;

import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.reconciliation.GatewayPayment.GatewayPaymentStatus;
import com.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The DEFAULT reconciliation gateway source, used when there is no activated
 * Razorpay account (i.e. app.reconciliation.gateway-provider is anything other
 * than "razorpay", which includes the shipped default "simulated").
 *
 * ── Why this exists ──────────────────────────────────────────────────────
 * Reconciliation compares OUR records against the GATEWAY's records. Without a
 * live Razorpay account we can't call their Payments API - but the valuable,
 * interview-defensible part of this feature is the COMPARISON engine
 * ({@link ReconciliationService}), not the fetch. So this source stands in for
 * "the gateway's records" and lets the entire engine run, be unit/integration
 * tested, and be demoed end-to-end. The moment a real Razorpay account is
 * available, flipping one property swaps in {@code RazorpayGatewaySource} with
 * ZERO changes to the engine.
 *
 * ── How it fabricates a realistic "gateway view" ─────────────────────────
 * It derives the gateway side from our OWN local top-ups for the day, applying
 * a deterministic transformation so that a demo actually produces a few
 * mismatches to see (otherwise reconciliation would be a boring "0 mismatches"
 * every time):
 *
 *   • A local SUCCESS top-up (with a payment id) normally appears on the
 *     gateway side as CAPTURED with the SAME amount  -> a clean match.
 *   • BUT for a small, deterministic subset we intentionally introduce drift
 *     to exercise every MismatchType:
 *       - one bucket: gateway reports FAILED though we say SUCCESS -> STATUS_MISMATCH
 *       - one bucket: gateway reports a slightly different amount   -> AMOUNT_MISMATCH
 *       - one bucket: gateway OMITS the payment entirely            -> MISSING_AT_GATEWAY
 *   • It also injects one purely-gateway payment with no local match ->
 *     MISSING_LOCALLY.
 *
 * The transformation keys off each transaction's id modulo a small number, so
 * it is DETERMINISTIC (the same input always yields the same "gateway view") -
 * which keeps tests and demos repeatable rather than randomly flaky.
 *
 * This class does NOT run in the "razorpay" profile (see @ConditionalOnProperty
 * with matchIfMissing = true: it's active for the default and for "simulated",
 * and absent when the provider is explicitly "razorpay").
 */
@Component
@ConditionalOnProperty(
        name = "app.reconciliation.gateway-provider",
        havingValue = "simulated",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SimulatedGatewaySource implements PaymentGatewayReconciliationSource {

    private final TransactionRepository transactionRepository;

    @Override
    public List<GatewayPayment> fetchPaymentsForDate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        List<Transaction> localTopups = transactionRepository.findTopupsCreatedBetween(from, to);

        List<GatewayPayment> gatewayView = new ArrayList<>();

        for (Transaction t : localTopups) {
            // Only paid top-ups (those with a payment id) can appear on the
            // gateway side; unpaid CREATED/PENDING ones have no gateway record.
            String paymentId = t.getRazorpayPaymentId();
            if (paymentId == null || paymentId.isBlank() || t.getStatus() != TransactionStatus.SUCCESS) {
                continue;
            }

            // Deterministic drift based on the transaction id, so each SUCCESS
            // top-up falls into one of four buckets. Most are clean matches.
            long bucket = t.getId() % 7;
            switch ((int) bucket) {
                case 0 ->
                    // STATUS_MISMATCH: gateway says this "succeeded" payment actually FAILED.
                        gatewayView.add(new GatewayPayment(
                                paymentId, GatewayPaymentStatus.FAILED, t.getAmount(), t.getCurrency()));
                case 1 ->
                    // AMOUNT_MISMATCH: gateway reports a slightly different amount.
                        gatewayView.add(new GatewayPayment(
                                paymentId, GatewayPaymentStatus.CAPTURED,
                                t.getAmount().add(java.math.BigDecimal.ONE), t.getCurrency()));
                case 2 -> {
                    // MISSING_AT_GATEWAY: deliberately omit this payment from the gateway view.
                    log.debug("Simulated gateway omitting payment {} to demonstrate MISSING_AT_GATEWAY", paymentId);
                }
                default ->
                    // Clean match: same id, CAPTURED, same amount.
                        gatewayView.add(new GatewayPayment(
                                paymentId, GatewayPaymentStatus.CAPTURED, t.getAmount(), t.getCurrency()));
            }
        }

        // Inject exactly one gateway-only payment with no local counterpart, to
        // demonstrate MISSING_LOCALLY (only when there was at least some local
        // activity, so an empty day stays cleanly empty).
        if (!localTopups.isEmpty()) {
            gatewayView.add(new GatewayPayment(
                    "pay_sim_orphan_" + date, GatewayPaymentStatus.CAPTURED,
                    new java.math.BigDecimal("999.00"), "INR"));
        }

        log.info("Simulated gateway produced {} payment record(s) for {}", gatewayView.size(), date);
        return gatewayView;
    }

    @Override
    public String providerName() {
        return "simulated";
    }
}
