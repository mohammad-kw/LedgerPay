package com.wallet.service;

import com.wallet.dto.MockTopUpResponse;
import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStateMachine;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * DEV / DEMO ONLY - an "instant" wallet top-up that bypasses Razorpay.
 *
 * WHY THIS EXISTS
 * ---------------
 * The real top-up flow needs a Razorpay account whose KYC/activation is
 * complete before its API keys will authenticate. Until that's done (or if you
 * simply don't want to submit real PAN/bank details for a portfolio project),
 * the Add-Money button can't reach Razorpay. This service lets you fund a
 * wallet directly so the REST of the app - transfers, the double-entry ledger,
 * balances, transaction history - can be built and demoed end-to-end.
 *
 * WHY IT'S A FAITHFUL SIMULATION, NOT A CHEAT
 * -------------------------------------------
 * It deliberately reuses the EXACT same money-movement logic as the real
 * WebhookService.handleCaptured() path (PROJECT_SPEC.md Section 3.2):
 *   - it creates a TOPUP transaction and drives it CREATED -> SUCCESS via the
 *     {@link TransactionStateMachine} (no illegal status jumps),
 *   - it writes a single CREDIT {@link LedgerEntry} to the wallet (a top-up's
 *     counter-party is the outside world, so one CREDIT is the correct
 *     representation - same as the webhook),
 *   - it updates the cached {@link Wallet#getBalance()} to match the ledger,
 *   - and it does ALL of that inside one {@link Transactional} method, so the
 *     status change, ledger row, and balance update commit together or not at
 *     all. The cached balance can never diverge from the ledger.
 * The ONLY thing skipped is the external Razorpay call + signature-verified
 * webhook. The financially-correct core is identical.
 *
 * WHY IT CAN NEVER RUN IN PRODUCTION
 * ----------------------------------
 * An endpoint that mints money on demand must never be reachable in a real
 * deployment. Two independent guards enforce that:
 *   1. {@code @ConditionalOnProperty(name = "app.mock-topup.enabled",
 *      havingValue = "true")} - this bean (and its controller) are only
 *      created when that flag is explicitly true. With the default (false)
 *      the bean doesn't exist and the route returns 404.
 *   2. The frontend only renders the mock button under Vite's
 *      {@code import.meta.env.DEV}, so it's stripped from production builds.
 * Keep the flag false anywhere real.
 */
@Service
@ConditionalOnProperty(name = "app.mock-topup.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MockTopUpService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Instantly credit {@code amount} to the authenticated user's wallet,
     * simulating a captured Razorpay payment.
     *
     * The whole method is {@link Transactional}: the transaction row, the
     * ledger CREDIT, and the balance update are one atomic unit (Section 3.2).
     *
     * IDEMPOTENCY (Section 3.1): identical to the real flows - if this
     * Idempotency-Key was already used, we return the ORIGINAL result instead
     * of crediting the wallet a second time.
     *
     * @param userId         the authenticated user's id (from their JWT)
     * @param amount         the rupee amount to add (already bean-validated positive)
     * @param idempotencyKey the client-generated Idempotency-Key header value
     */
    @Transactional
    public MockTopUpResponse mockTopUp(Long userId, BigDecimal amount, String idempotencyKey) {
        // --- Idempotency short-circuit -------------------------------------
        // If we've seen this key before, return the ALREADY-credited result
        // rather than crediting again.
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transaction txn = existing.get();
            log.info("[MOCK] Idempotency-Key {} already processed -> returning existing txn id={}",
                    idempotencyKey, txn.getId());
            BigDecimal balance = txn.getReceiverWallet() != null
                    ? txn.getReceiverWallet().getBalance()
                    : BigDecimal.ZERO;
            return MockTopUpResponse.from(txn, balance);
        }

        // --- Resolve the caller's wallet -----------------------------------
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for the current user"));

        // --- Create the transaction, starting CREATED ----------------------
        // A TOPUP is money coming IN, so receiverWallet = this wallet and
        // senderWallet = null (the money originates outside our system). We
        // start CREATED and promote to SUCCESS once the ledger is written,
        // exactly like the webhook path.
        Transaction txn = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.CREATED)
                .receiverWallet(wallet)
                .amount(amount)
                .currency(wallet.getCurrency())
                .build();
        txn = transactionRepository.save(txn);

        // --- Double-entry ledger + cached balance (Section 3.2) ------------
        // Exactly one CREDIT row for a top-up (the counter-party is the
        // outside world, not an internal wallet). Compute the new balance from
        // the CURRENT cached balance, record it as balance_after.
        BigDecimal newBalance = wallet.getBalance().add(amount);

        LedgerEntry credit = LedgerEntry.builder()
                .transaction(txn)
                .wallet(wallet)
                .entryType(EntryType.CREDIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .build();
        ledgerEntryRepository.save(credit);

        // Update the cached balance to match the ledger we just wrote. Both
        // commit together under this @Transactional, so they can't diverge.
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        // --- Promote CREATED -> SUCCESS (guarded by the state machine) -----
        TransactionStateMachine.assertCanTransition(txn.getStatus(), TransactionStatus.SUCCESS);
        txn.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(txn);

        log.info("[MOCK] Top-up SUCCESS: txn={} wallet={} amount={} newBalance={}",
                txn.getId(), wallet.getId(), amount, newBalance);

        return MockTopUpResponse.from(txn, newBalance);
    }
}
