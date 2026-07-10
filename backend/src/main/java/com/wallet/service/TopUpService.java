package com.wallet.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.wallet.config.RazorpayProperties;
import com.wallet.dto.TopUpInitiateResponse;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.exception.PaymentGatewayException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Business logic for POST /api/wallet/topup/initiate (PROJECT_SPEC.md
 * Section 5 & Phase 2). This is step ONE of a two-step top-up flow:
 *
 *   STEP 1 (this class):  create a Razorpay Order + a local Transaction row
 *                         with status CREATED, and hand the order id back to
 *                         the browser so it can open Razorpay Checkout.
 *   STEP 2 (a LATER phase, via webhook): Razorpay calls our server to
 *                         confirm the payment actually succeeded; only THEN
 *                         do we move the transaction to SUCCESS and credit
 *                         the wallet (writing ledger entries).
 *
 * The critical rule this class must honour (PROJECT_SPEC.md Section 3.3 &
 * 3.4): it must NEVER mark the transaction SUCCESS or touch the wallet
 * balance. Creating an order is not the same as being paid. A user could
 * open the checkout popup and simply close it - the money never arrives.
 * The ONLY trustworthy confirmation is the signed webhook (Section 3.4:
 * "Webhooks as source of truth, not the browser redirect"), which is built
 * in Phase 3. So here we deliberately stop at status CREATED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TopUpService {

    /** Razorpay (like most payment gateways) works in the minor currency unit - paise for INR. ₹1.00 = 100 paise. */
    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final RazorpayClient razorpayClient;
    private final RazorpayProperties razorpayProperties;

    /**
     * Create a Razorpay order for the given user + amount and persist a
     * CREATED transaction, returning what the frontend needs to open
     * Checkout.
     *
     * IDEMPOTENCY (PROJECT_SPEC.md Section 3.1): the client sends a unique
     * Idempotency-Key header per logical request. If we've already processed
     * that key (e.g. the user double-clicked, or the network auto-retried),
     * we must NOT create a second Razorpay order / second transaction - we
     * return the ORIGINAL one instead. We check for an existing key up front
     * here; the UNIQUE constraint on transactions.idempotency_key is the
     * final safety net if two identical requests race past that check at the
     * same instant (the second insert then fails at the DB level).
     *
     * @Transactional wraps the whole method: the "look up wallet + save
     * transaction" work commits atomically. (The external Razorpay HTTP call
     * itself is not transactional - see the ordering note inline below.)
     *
     * @param userId         the authenticated user's id (from their JWT)
     * @param amount         the rupee amount to add (already bean-validated as positive)
     * @param idempotencyKey the client-generated Idempotency-Key header value
     */
    @Transactional
    public TopUpInitiateResponse initiateTopUp(Long userId, BigDecimal amount, String idempotencyKey) {
        // --- Idempotency short-circuit -------------------------------------
        // If we've seen this key before, rebuild the response from the
        // ALREADY-created transaction instead of creating a new order.
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transaction txn = existing.get();
            log.info("Idempotency-Key {} already processed -> returning existing transaction id={}",
                    idempotencyKey, txn.getId());
            return buildResponse(txn, toPaise(txn.getAmount()));
        }

        // --- Resolve the caller's wallet -----------------------------------
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for the current user"));

        long amountInPaise = toPaise(amount);

        // --- Create the order at Razorpay ----------------------------------
        // We do this BEFORE saving our row so we can store the returned
        // razorpay_order_id on the transaction. If Razorpay errors, we throw
        // and @Transactional rolls back - so we never persist a CREATED
        // transaction that has no matching Razorpay order.
        String razorpayOrderId = createRazorpayOrder(amountInPaise, wallet.getCurrency(), idempotencyKey);

        // --- Persist our own CREATED transaction ---------------------------
        // receiverWallet = this wallet (a top-up is money coming IN to it);
        // senderWallet = null (the money originates outside our system, from
        // Razorpay). status = CREATED - NOT success. See class javadoc.
        Transaction transaction = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.CREATED)
                .receiverWallet(wallet)
                .amount(amount)
                .currency(wallet.getCurrency())
                .razorpayOrderId(razorpayOrderId)
                .build();
        transaction = transactionRepository.save(transaction);

        log.info("Top-up initiated: transaction id={} order={} amountPaise={} userId={}",
                transaction.getId(), razorpayOrderId, amountInPaise, userId);

        return buildResponse(transaction, amountInPaise);
    }

    /**
     * Calls Razorpay's Orders API to create a new order. Razorpay's SDK
     * speaks in org.json.JSONObject, so we build the request as one:
     *   amount   - integer, in paise (the minor unit).
     *   currency - e.g. "INR".
     *   receipt  - OUR own reference string for this order. We use the
     *              idempotency key so an order can be traced back to the
     *              exact request that created it. (Razorpay also de-dupes on
     *              receipt to a degree, but we don't rely on that - our own
     *              DB check above is the real idempotency guard.)
     *
     * Any failure from Razorpay is wrapped in a RazorpayException (checked);
     * we translate it into our own PaymentGatewayException (unchecked) so it
     * flows through GlobalExceptionHandler as a 502 Bad Gateway - the honest
     * status for "an upstream provider failed", not a 500 that would wrongly
     * blame our own code.
     */
    private String createRazorpayOrder(long amountInPaise, String currency, String receipt) {
        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", currency);
            orderRequest.put("receipt", receipt);

            Order order = razorpayClient.orders.create(orderRequest);

            // The SDK returns a wrapper around Razorpay's JSON response; the
            // order id lives under the "id" key (e.g. "order_ABC123").
            String orderId = order.get("id");
            log.debug("Created Razorpay order {} for {} paise", orderId, amountInPaise);
            return orderId;
        } catch (RazorpayException ex) {
            log.error("Failed to create Razorpay order for {} paise: {}", amountInPaise, ex.getMessage());
            throw new PaymentGatewayException("Could not create payment order with Razorpay", ex);
        }
    }

    /** Assemble the response DTO from a persisted transaction + the paise amount, injecting our (non-secret) key id for Checkout. */
    private TopUpInitiateResponse buildResponse(Transaction txn, long amountInPaise) {
        return new TopUpInitiateResponse(
                txn.getId(),
                txn.getRazorpayOrderId(),
                razorpayProperties.keyId(),
                amountInPaise,
                txn.getCurrency()
        );
    }

    /**
     * Convert a rupee BigDecimal (e.g. 500.00) into an integer paise amount
     * (50000) for Razorpay. We multiply by 100 with exact BigDecimal
     * arithmetic and call longValueExact(), which THROWS if the value has
     * any leftover fraction - so a bad amount fails loudly instead of being
     * silently rounded. (Bean validation already restricts input to <= 2
     * decimals, so this is a belt-and-braces safety check.)
     */
    private long toPaise(BigDecimal rupees) {
        return rupees.multiply(PAISE_PER_RUPEE).longValueExact();
    }
}
