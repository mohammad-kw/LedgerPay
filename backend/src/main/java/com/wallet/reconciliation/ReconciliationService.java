package com.wallet.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.entity.ReconciliationLog;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.reconciliation.GatewayPayment.GatewayPaymentStatus;
import com.wallet.reconciliation.ReconciliationMismatch.MismatchType;
import com.wallet.repository.ReconciliationLogRepository;
import com.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * THE RECONCILIATION ENGINE (PROJECT_SPEC.md Section 3.5) - the project's key
 * differentiator. It answers one question for a given day: "does our record of
 * what happened agree with the payment gateway's record of what happened?" and
 * logs every disagreement.
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ WHY RECONCILIATION EXISTS (the interview one-liner)                     │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ Webhooks can be missed, arrive out of order, or be dropped; our server │
 * │ can crash mid-processing; bugs happen. Any of these can leave our DB   │
 * │ subtly out of sync with the gateway - we think a payment succeeded when │
 * │ it failed, or we never recorded one that did succeed. Reconciliation is │
 * │ the periodic SAFETY NET that catches those drifts by comparing the two  │
 * │ independent sources of truth and flagging mismatches for a human to fix.│
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * ── THE MATCHING / MISMATCH LOGIC (defend this in interviews) ────────────
 * The join key between the two worlds is the GATEWAY PAYMENT ID (Razorpay's
 * "pay_..." id), which we store locally as {@code razorpay_payment_id} once a
 * webhook confirms a payment. For a given day we build:
 *
 *   • LOCAL side  : our TOPUP transactions created that day (only top-ups go
 *                   through the gateway; transfers/withdrawals are internal
 *                   and have no gateway counterpart), keyed by payment id.
 *   • GATEWAY side: the gateway's payments for that day, keyed by payment id.
 *
 * We then walk BOTH sides so nothing is missed in either direction:
 *
 *   1. For each GATEWAY payment:
 *        - no local txn with that payment id  -> MISSING_LOCALLY
 *          (gateway took money we never recorded - most serious)
 *        - local txn exists but statuses conflict (e.g. we say SUCCESS,
 *          gateway says FAILED/REFUNDED)      -> STATUS_MISMATCH
 *        - statuses agree it succeeded but amounts differ -> AMOUNT_MISMATCH
 *
 *   2. For each LOCAL SUCCESS top-up with a payment id that the gateway
 *      DIDN'T return                          -> MISSING_AT_GATEWAY
 *      (we credited a wallet the gateway has no record of)
 *
 * Walking both sides is essential: checking only "local vs gateway" would
 * miss payments that exist ONLY at the gateway, and checking only "gateway vs
 * local" would miss wallets we credited with no gateway backing. A correct
 * reconciliation is a FULL OUTER comparison, not a one-directional lookup.
 *
 * ── What we deliberately DON'T reconcile ─────────────────────────────────
 *   • Local top-ups still in CREATED/PENDING with NO payment id yet: these
 *     simply haven't been paid (the user opened checkout and hasn't finished).
 *     They're "in flight", not mismatches, so we skip them rather than raise
 *     false MISSING_AT_GATEWAY noise. (A separate "stuck in CREATED for days"
 *     staleness check would be a reasonable future addition, but it's a
 *     different concern from gateway reconciliation.)
 *
 * ── Read-only by design ──────────────────────────────────────────────────
 * This job only DETECTS and LOGS mismatches; it never auto-"fixes" balances.
 * Auto-correcting financial records from a batch job is dangerous (a bug could
 * corrupt many wallets at once); the safe, industry-standard approach is to
 * surface discrepancies for a human/controlled process to resolve. The write
 * it DOES perform is purely the audit row in reconciliation_logs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final TransactionRepository transactionRepository;
    private final ReconciliationLogRepository reconciliationLogRepository;
    private final PaymentGatewayReconciliationSource gatewaySource;
    private final ObjectMapper objectMapper;

    /**
     * Run reconciliation for a single calendar day and persist a
     * {@link ReconciliationLog} summarising the outcome.
     *
     * @Transactional so the log row is written atomically. (The engine itself
     * is read-only against transactions; the only write is the audit log.)
     *
     * @param date the calendar day to reconcile
     * @return the persisted log row (also returned to the admin endpoint)
     */
    @Transactional
    public ReconciliationLog reconcileForDate(LocalDate date) {
        log.info("Reconciliation starting for {} against provider '{}'", date, gatewaySource.providerName());
        try {
            // --- Gather BOTH sides, keyed by gateway payment id ------------
            // Local: top-ups created during [date 00:00, nextDay 00:00).
            LocalDateTime from = date.atStartOfDay();
            LocalDateTime to = date.plusDays(1).atStartOfDay();
            List<Transaction> localTopups = transactionRepository.findTopupsCreatedBetween(from, to);

            // Index local transactions that HAVE a payment id (only those can
            // be matched against the gateway). Two top-ups should never share a
            // payment id, but if data were corrupt we keep the first and log it.
            Map<String, Transaction> localByPaymentId = localTopups.stream()
                    .filter(t -> t.getRazorpayPaymentId() != null && !t.getRazorpayPaymentId().isBlank())
                    .collect(Collectors.toMap(
                            Transaction::getRazorpayPaymentId,
                            Function.identity(),
                            (a, b) -> {
                                log.warn("Duplicate local payment id {} on txns {} and {}; keeping {}",
                                        a.getRazorpayPaymentId(), a.getId(), b.getId(), a.getId());
                                return a;
                            }));

            // Gateway: whatever the (real or simulated) source reports for the day.
            List<GatewayPayment> gatewayPayments = gatewaySource.fetchPaymentsForDate(date);
            Map<String, GatewayPayment> gatewayByPaymentId = gatewayPayments.stream()
                    .collect(Collectors.toMap(GatewayPayment::gatewayPaymentId, Function.identity(), (a, b) -> a));

            List<ReconciliationMismatch> mismatches = new ArrayList<>();
            Set<String> gatewayIdsSeen = new HashSet<>();

            // --- Direction 1: walk every GATEWAY payment -------------------
            for (GatewayPayment gp : gatewayPayments) {
                gatewayIdsSeen.add(gp.gatewayPaymentId());
                Transaction local = localByPaymentId.get(gp.gatewayPaymentId());

                if (local == null) {
                    // Gateway has a payment we have no local record of.
                    mismatches.add(new ReconciliationMismatch(
                            MismatchType.MISSING_LOCALLY,
                            null, gp.gatewayPaymentId(),
                            null, gp.status().name(),
                            null, gp.amount(),
                            "Gateway has payment " + gp.gatewayPaymentId()
                                    + " but there is no local transaction with that payment id"));
                    continue;
                }

                // Both sides know this payment - compare OUTCOME, then AMOUNT.
                boolean localSaysSuccess = local.getStatus() == TransactionStatus.SUCCESS;
                boolean gatewaySaysSuccess = gp.status() == GatewayPaymentStatus.CAPTURED;

                if (localSaysSuccess != gatewaySaysSuccess) {
                    mismatches.add(new ReconciliationMismatch(
                            MismatchType.STATUS_MISMATCH,
                            local.getId(), gp.gatewayPaymentId(),
                            local.getStatus().name(), gp.status().name(),
                            local.getAmount(), gp.amount(),
                            "Local status " + local.getStatus() + " disagrees with gateway status " + gp.status()));
                } else if (gatewaySaysSuccess
                        && local.getAmount().compareTo(gp.amount()) != 0) {
                    // Only meaningful to compare amounts when both agree it succeeded.
                    mismatches.add(new ReconciliationMismatch(
                            MismatchType.AMOUNT_MISMATCH,
                            local.getId(), gp.gatewayPaymentId(),
                            local.getStatus().name(), gp.status().name(),
                            local.getAmount(), gp.amount(),
                            "Amount differs: local=" + local.getAmount() + " gateway=" + gp.amount()));
                }
            }

            // --- Direction 2: local SUCCESS top-ups the gateway didn't return
            for (Transaction local : localTopups) {
                String pid = local.getRazorpayPaymentId();
                boolean hasPaymentId = pid != null && !pid.isBlank();
                if (local.getStatus() == TransactionStatus.SUCCESS
                        && hasPaymentId
                        && !gatewayIdsSeen.contains(pid)) {
                    mismatches.add(new ReconciliationMismatch(
                            MismatchType.MISSING_AT_GATEWAY,
                            local.getId(), pid,
                            local.getStatus().name(), null,
                            local.getAmount(), null,
                            "Local transaction " + local.getId() + " is SUCCESS with payment id " + pid
                                    + " but the gateway returned no such payment"));
                }
                // Note: CREATED/PENDING local top-ups without a payment id are
                // "in flight", not mismatches - deliberately skipped (see class javadoc).
            }

            // --- Persist the audit log -------------------------------------
            int totalChecked = localTopups.size() + gatewayPayments.size();
            ReconciliationLog logRow = ReconciliationLog.builder()
                    .runDate(date)
                    .totalChecked(totalChecked)
                    .mismatchesFound(mismatches.size())
                    .mismatchDetailsJson(toJson(mismatches))
                    .status("COMPLETED")
                    .build();
            logRow = reconciliationLogRepository.save(logRow);

            log.info("Reconciliation for {} COMPLETED: checked={} mismatches={}",
                    date, totalChecked, mismatches.size());
            return logRow;

        } catch (Exception ex) {
            // Any failure (e.g. the real gateway API erroring) is itself
            // recorded as a FAILED run, so the audit trail shows the attempt
            // rather than silently vanishing.
            log.error("Reconciliation for {} FAILED: {}", date, ex.getMessage(), ex);
            ReconciliationLog failed = ReconciliationLog.builder()
                    .runDate(date)
                    .totalChecked(0)
                    .mismatchesFound(0)
                    .mismatchDetailsJson("{\"error\":\"" + safe(ex.getMessage()) + "\"}")
                    .status("FAILED")
                    .build();
            return reconciliationLogRepository.save(failed);
        }
    }

    /** All past runs, newest first, for GET /api/admin/reconciliation/logs. */
    @Transactional(readOnly = true)
    public List<ReconciliationLog> getAllLogs() {
        return reconciliationLogRepository.findAllByOrderByIdDesc();
    }

    private String toJson(List<ReconciliationMismatch> mismatches) {
        try {
            return objectMapper.writeValueAsString(mismatches);
        } catch (JsonProcessingException e) {
            // Serialization of our own simple records should never fail; if it
            // somehow does, don't lose the run - store a fallback.
            log.error("Failed to serialize reconciliation mismatches to JSON", e);
            return "[]";
        }
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace("\"", "'");
    }
}
