package com.wallet.reconciliation;

import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.wallet.reconciliation.GatewayPayment.GatewayPaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The REAL reconciliation gateway source: fetches payment records straight
 * from Razorpay's Payments API (PROJECT_SPEC.md Section 3.5 & 6).
 *
 * This bean is ONLY created when app.reconciliation.gateway-provider=razorpay,
 * which requires an ACTIVATED Razorpay account whose keys authenticate. Until
 * then the app uses {@link SimulatedGatewaySource} instead, and the
 * reconciliation ENGINE ({@link ReconciliationService}) is identical either
 * way - it depends only on the {@link PaymentGatewayReconciliationSource}
 * interface. Swapping to real Razorpay is a one-line config change.
 *
 * ── How the fetch works ──────────────────────────────────────────────────
 * Razorpay's "fetch all payments" endpoint accepts a `from`/`to` UNIX-second
 * time window plus `count`/`skip` for pagination. We:
 *   1. convert the requested calendar day into a [startOfDay, startOfNextDay)
 *      window in epoch seconds (UTC),
 *   2. page through the results (Razorpay caps `count` at 100 per call),
 *   3. map each raw payment into our neutral {@link GatewayPayment}, converting
 *      Razorpay's amount-in-PAISE to rupees and its status string to our enum.
 *
 * Razorpay payment statuses we care about: "captured" (money settled) ->
 * CAPTURED; "failed" -> FAILED; "refunded" -> REFUNDED. Other transient
 * statuses ("created"/"authorized") are payments not yet finalised, which we
 * skip - reconciliation only compares FINAL outcomes.
 */
@Component
@ConditionalOnProperty(name = "app.reconciliation.gateway-provider", havingValue = "razorpay")
@Slf4j
public class RazorpayGatewaySource implements PaymentGatewayReconciliationSource {

    /** Razorpay caps the page size at 100 records per fetch. */
    private static final int PAGE_SIZE = 100;
    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final RazorpayClient razorpayClient;

    public RazorpayGatewaySource(RazorpayClient razorpayClient) {
        this.razorpayClient = razorpayClient;
    }

    @Override
    public List<GatewayPayment> fetchPaymentsForDate(LocalDate date) {
        long from = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long to = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);

        List<GatewayPayment> results = new ArrayList<>();
        int skip = 0;
        try {
            while (true) {
                JSONObject options = new JSONObject();
                options.put("from", from);
                options.put("to", to);
                options.put("count", PAGE_SIZE);
                options.put("skip", skip);

                List<Payment> page = razorpayClient.payments.fetchAll(options);
                if (page == null || page.isEmpty()) {
                    break;
                }

                for (Payment p : page) {
                    GatewayPayment mapped = mapPayment(p);
                    if (mapped != null) {
                        results.add(mapped);
                    }
                }

                if (page.size() < PAGE_SIZE) {
                    break; // last page
                }
                skip += PAGE_SIZE;
            }
        } catch (RazorpayException ex) {
            // Surface as an unchecked exception so ReconciliationService records
            // the run as FAILED (its try/catch) rather than silently returning
            // a partial/empty gateway view that would produce bogus mismatches.
            throw new IllegalStateException("Failed to fetch payments from Razorpay for " + date, ex);
        }

        log.info("Razorpay returned {} payment record(s) for {}", results.size(), date);
        return results;
    }

    /** Map a raw Razorpay Payment into our neutral shape; return null for non-final statuses we skip. */
    private GatewayPayment mapPayment(Payment p) {
        String id = p.get("id");
        String status = p.get("status");
        String currency = p.has("currency") ? p.get("currency") : "INR";
        // Razorpay amounts are integers in paise; convert to rupees.
        Integer amountPaise = p.get("amount");
        BigDecimal amountRupees = new BigDecimal(amountPaise).divide(PAISE_PER_RUPEE);

        GatewayPaymentStatus mappedStatus = switch (status) {
            case "captured" -> GatewayPaymentStatus.CAPTURED;
            case "failed" -> GatewayPaymentStatus.FAILED;
            case "refunded" -> GatewayPaymentStatus.REFUNDED;
            // "created", "authorized", etc. are not final outcomes - skip them.
            default -> null;
        };
        if (mappedStatus == null) {
            return null;
        }
        return new GatewayPayment(id, mappedStatus, amountRupees, currency);
    }

    @Override
    public String providerName() {
        return "razorpay";
    }
}
