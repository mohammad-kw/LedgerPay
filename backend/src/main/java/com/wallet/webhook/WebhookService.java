package com.wallet.webhook;

import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStateMachine;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.Wallet;
import com.wallet.entity.WebhookEvent;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The heart of Phase 3: turning a verified Razorpay webhook into real,
 * financially-correct state changes (PROJECT_SPEC.md Section 3.3, 3.4, 3.2).
 * WebhookController stays thin and hands us two things: the RAW request body
 * and the X-Razorpay-Signature header. Everything of substance happens here.
 *
 * The flow, in order (each step guards the next):
 *   1. VERIFY the signature. If it fails, we stop immediately and do nothing
 *      else - an unverified webhook is untrusted and must never touch money.
 *   2. DEDUPE on the Razorpay event id. Razorpay may deliver the same event
 *      more than once (at-least-once delivery); if we've already stored this
 *      event id, we skip re-processing so a wallet can't be credited twice.
 *   3. STORE the raw event (signatureVerified = true) as an audit record.
 *   4. DISPATCH on event type:
 *        payment.captured -> promote the matching transaction to SUCCESS,
 *                            write the double-entry ledger rows, and update
 *                            the cached wallet balance - all atomically.
 *        payment.failed   -> move the transaction to FAILED (no ledger, no
 *                            balance change - no money moved).
 *        anything else    -> stored for audit but otherwise ignored.
 *   5. MARK the event processed.
 *
 * WHY the balance update + ledger insert must be ONE DB transaction
 * (Section 3.2): the cached Wallet.balance and the ledger rows are two
 * representations of the same truth. If we wrote ledger rows but a crash
 * stopped the balance update (or vice-versa), they'd disagree forever and the
 * wallet would be silently wrong. @Transactional makes the whole
 * handleCaptured unit all-or-nothing: either every row (transaction status,
 * ledger entry, new balance) commits together, or if anything throws, they
 * ALL roll back and nothing changed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private static final String EVENT_PAYMENT_CAPTURED = "payment.captured";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Entry point called by WebhookController.
     *
     * @param rawPayload      the exact raw JSON body Razorpay POSTed (needed
     *                        verbatim for signature verification - see
     *                        WebhookSignatureVerifier)
     * @param signatureHeader the incoming X-Razorpay-Signature header value
     * @return a small result telling the controller what HTTP status to send
     *         back (200 for accepted/duplicate/ignored, 400 for a bad
     *         signature). We deliberately never surface internal detail to the
     *         caller - Razorpay only needs to know "did you accept it?".
     *
     * The whole method is @Transactional so that the "store event + act on it"
     * work commits atomically. (Signature verification itself does no DB work,
     * but keeping it inside the boundary is harmless and simplifies reasoning.)
     */
    @Transactional
    public WebhookResult process(String rawPayload, String signatureHeader) {
        // --- Step 1: verify signature BEFORE anything else -----------------
        if (!signatureVerifier.isValid(rawPayload, signatureHeader)) {
            // Untrusted. Do not store, do not process. Tell the controller to
            // return 400 so a genuine misconfiguration is visible, while a
            // forged call simply gets rejected.
            return WebhookResult.INVALID_SIGNATURE;
        }

        // The payload is now trusted. Parse it to read the event id, type,
        // and the nested payment entity. org.json is already on the classpath
        // (pulled in by the Razorpay SDK) and is fine for this read-only pick.
        JSONObject root = new JSONObject(rawPayload);
        String eventType = root.optString("event", "");
        String eventId = extractEventId(root);

        // --- Step 2: dedupe on the Razorpay event id -----------------------
        if (eventId != null && webhookEventRepository.existsByRazorpayEventId(eventId)) {
            log.info("Duplicate webhook ignored: eventId={} type={}", eventId, eventType);
            // A duplicate is NOT an error - returning 200 tells Razorpay "we've
            // got it, stop retrying". Re-processing is what we must avoid, and
            // we just did.
            return WebhookResult.DUPLICATE;
        }

        // --- Step 3: store the raw, verified event (audit trail) -----------
        WebhookEvent event = WebhookEvent.builder()
                .razorpayEventId(eventId)
                .eventType(eventType)
                .payloadJson(rawPayload)
                .signatureVerified(true)
                .processed(false)
                .build();
        event = webhookEventRepository.save(event);

        // --- Step 4: dispatch on event type --------------------------------
        switch (eventType) {
            case EVENT_PAYMENT_CAPTURED -> handleCaptured(root);
            case EVENT_PAYMENT_FAILED -> handleFailed(root);
            default -> log.info("Webhook stored but not acted on (unhandled type): {}", eventType);
        }

        // --- Step 5: mark processed ----------------------------------------
        event.setProcessed(true);
        event.setProcessedAt(LocalDateTime.now());
        // (event is a managed entity inside this @Transactional method, so the
        // change is flushed on commit; the explicit save is for clarity.)
        webhookEventRepository.save(event);

        return WebhookResult.PROCESSED;
    }

    /**
     * payment.captured: the money really arrived. Promote our transaction
     * CREATED -> SUCCESS, record the payment id, and (the important part)
     * credit the wallet via a double-entry ledger row + cached-balance update.
     *
     * All of this runs inside the caller's @Transactional boundary, so the
     * status change, the ledger insert, and the balance update commit together
     * or not at all (Section 3.2).
     */
    private void handleCaptured(JSONObject root) {
        JSONObject payment = extractPaymentEntity(root);
        if (payment == null) {
            log.warn("payment.captured had no payment entity; skipping");
            return;
        }

        String orderId = payment.optString("order_id", null);
        String paymentId = payment.optString("id", null);

        Optional<Transaction> maybeTxn = findTransactionForOrder(orderId);
        if (maybeTxn.isEmpty()) {
            // We don't recognise this order. Log and move on - the event is
            // still stored/audited, but there's nothing of ours to update.
            log.warn("payment.captured for unknown order_id={} (paymentId={})", orderId, paymentId);
            return;
        }
        Transaction txn = maybeTxn.get();

        // Idempotency at the money level: if this transaction is already
        // SUCCESS (e.g. a duplicate that somehow slipped past the event-id
        // dedupe), do NOT credit the wallet a second time. canTransition
        // returns false for SUCCESS -> SUCCESS, which we treat as "already
        // done" rather than an error.
        if (txn.getStatus() == TransactionStatus.SUCCESS) {
            log.info("Transaction {} already SUCCESS; skipping duplicate credit", txn.getId());
            return;
        }

        // Guard the state transition (throws on anything illegal, e.g. if the
        // transaction was FAILED). CREATED -> SUCCESS is legal (see
        // TransactionStateMachine's javadoc for why this is the verified path).
        TransactionStateMachine.assertCanTransition(txn.getStatus(), TransactionStatus.SUCCESS);

        // The wallet being credited is the RECEIVER of a top-up (senderWallet
        // is null for TOPUP - money comes from outside our system).
        Wallet wallet = txn.getReceiverWallet();
        if (wallet == null) {
            log.error("Transaction {} has no receiver wallet; cannot credit", txn.getId());
            return;
        }

        BigDecimal amount = txn.getAmount();

        // --- Double-entry ledger + cached balance (Section 3.2) ------------
        // Compute the new balance from the CURRENT cached balance + amount.
        // For a TOPUP there is exactly ONE ledger row: a CREDIT to the user's
        // wallet. (A transfer, built later, will instead create a balanced
        // DEBIT+CREDIT pair. A top-up's counter-party is Razorpay/the outside
        // world, which we don't model as an internal wallet, so a single
        // CREDIT is the correct representation here.)
        BigDecimal newBalance = wallet.getBalance().add(amount);

        LedgerEntry credit = LedgerEntry.builder()
                .transaction(txn)
                .wallet(wallet)
                .entryType(EntryType.CREDIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .build();
        ledgerEntryRepository.save(credit);

        // Update the cached balance to match the ledger we just wrote. Because
        // we're in one transaction, these two facts can never diverge.
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        // Promote the transaction and record the Razorpay payment id.
        txn.setStatus(TransactionStatus.SUCCESS);
        txn.setRazorpayPaymentId(paymentId);
        transactionRepository.save(txn);

        log.info("Top-up SUCCESS: txn={} wallet={} amount={} newBalance={} paymentId={}",
                txn.getId(), wallet.getId(), amount, newBalance, paymentId);
    }

    /**
     * payment.failed: the payment did not go through. Move the transaction to
     * FAILED. Crucially we write NO ledger entries and do NOT touch the
     * balance - no money moved, so the ledger must stay silent (Section 3.2).
     */
    private void handleFailed(JSONObject root) {
        JSONObject payment = extractPaymentEntity(root);
        if (payment == null) {
            log.warn("payment.failed had no payment entity; skipping");
            return;
        }

        String orderId = payment.optString("order_id", null);
        String paymentId = payment.optString("id", null);

        Optional<Transaction> maybeTxn = findTransactionForOrder(orderId);
        if (maybeTxn.isEmpty()) {
            log.warn("payment.failed for unknown order_id={} (paymentId={})", orderId, paymentId);
            return;
        }
        Transaction txn = maybeTxn.get();

        // Terminal-state safety: don't move an already-SUCCESS or already-
        // FAILED transaction. Only act if FAILED is a legal next state.
        if (!TransactionStateMachine.canTransition(txn.getStatus(), TransactionStatus.FAILED)) {
            log.info("Transaction {} in state {}; not moving to FAILED", txn.getId(), txn.getStatus());
            return;
        }

        txn.setStatus(TransactionStatus.FAILED);
        txn.setRazorpayPaymentId(paymentId);
        transactionRepository.save(txn);

        log.info("Top-up FAILED: txn={} order={} paymentId={}", txn.getId(), orderId, paymentId);
    }

    /** Null-safe lookup of our transaction by Razorpay order id. */
    private Optional<Transaction> findTransactionForOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return transactionRepository.findByRazorpayOrderId(orderId);
    }

    /**
     * Razorpay's event id. Razorpay sends it in the "x-razorpay-event-id"
     * HTTP header, but it is not part of the JSON body. Rather than thread the
     * header all the way down, we derive a stable unique id from the payment
     * entity's own id, which is unique per payment and stable across retries
     * of the same event. If a payment id isn't present we fall back to null,
     * and the caller simply won't dedupe on it.
     *
     * (In a later refinement we could pass the real x-razorpay-event-id header
     * through for an even stronger guarantee; the payment id is sufficient for
     * the top-up flow because each captured/failed event concerns exactly one
     * payment.)
     */
    private String extractEventId(JSONObject root) {
        JSONObject payment = extractPaymentEntity(root);
        String paymentId = payment == null ? null : payment.optString("id", null);
        String event = root.optString("event", "");
        // Combine type + payment id so a "captured" and a hypothetical later
        // event about the same payment don't collide on the unique constraint.
        return (paymentId == null) ? null : (event + ":" + paymentId);
    }

    /**
     * Dig the payment entity out of Razorpay's webhook envelope. The shape is:
     *   { "event": "...", "payload": { "payment": { "entity": { ...here... }}}}
     * We navigate defensively with opt* so a malformed/unexpected payload
     * returns null instead of throwing.
     */
    private JSONObject extractPaymentEntity(JSONObject root) {
        JSONObject payload = root.optJSONObject("payload");
        if (payload == null) {
            return null;
        }
        JSONObject payment = payload.optJSONObject("payment");
        if (payment == null) {
            return null;
        }
        return payment.optJSONObject("entity");
    }

    /**
     * Tiny result type so the controller can map outcomes to HTTP statuses
     * without knowing any internal detail.
     */
    public enum WebhookResult {
        /** Signature verified and the event was handled (or intentionally ignored by type). */
        PROCESSED,
        /** Already-seen event id; safely skipped. Still a 200 so Razorpay stops retrying. */
        DUPLICATE,
        /** Signature check failed; nothing was stored or processed. Maps to 400. */
        INVALID_SIGNATURE
    }
}
