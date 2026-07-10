package com.wallet.service;

import com.wallet.dto.TransferResponse;
import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStateMachine;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.exception.InvalidTransferException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wallet-to-wallet money transfer (PROJECT_SPEC.md Section 5, and the Phase 2
 * "Transfer money between users" item - now built WITH idempotency, the
 * ledger, and the state machine since those exist).
 *
 * This is the single most correctness-critical piece of business logic in the
 * whole project, because it MOVES MONEY between two accounts. Everything about
 * how it's written below is in service of one goal: it must be impossible for
 * the system to end up in a state where money was taken from one wallet but
 * not given to the other, or where a cached balance disagrees with the ledger.
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ THE BALANCE-CHECK + DOUBLE-ENTRY LEDGER LOGIC (read this in an          │
 * │ interview you WILL be asked to walk through it):                        │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ 1. DEDUPE first (idempotency): if this Idempotency-Key was already used,│
 * │    return the original result. Never move money twice for one request.  │
 * │                                                                         │
 * │ 2. VALIDATE the request against reality BEFORE any writes:              │
 * │      - sender must have a wallet,                                        │
 * │      - receiver must exist and have a wallet,                           │
 * │      - sender != receiver (can't pay yourself),                         │
 * │      - sender.balance >= amount  (THE balance check).                   │
 * │    If any fails we throw, and because we haven't written anything yet   │
 * │    (and are @Transactional) nothing changes.                            │
 * │                                                                         │
 * │ 3. MOVE THE MONEY as a balanced double-entry pair (Section 3.2):        │
 * │      newSender   = sender.balance   - amount                            │
 * │      newReceiver = receiver.balance + amount                            │
 * │      write LedgerEntry DEBIT  amount on sender   (balance_after=newSender)│
 * │      write LedgerEntry CREDIT amount on receiver (balance_after=newReceiver)│
 * │    The DEBIT and CREDIT are equal and opposite - they NET TO ZERO, which │
 * │    is the defining invariant of double-entry bookkeeping and the thing  │
 * │    that makes the whole system auditable. No money is created or        │
 * │    destroyed; it only moves.                                            │
 * │                                                                         │
 * │ 4. UPDATE BOTH cached balances to match the entries we just wrote.      │
 * │                                                                         │
 * │ 5. Mark the transaction SUCCESS (guarded by the state machine).         │
 * │                                                                         │
 * │ ALL of steps 3-5 happen inside ONE @Transactional method. That is what  │
 * │ guarantees atomicity: the two ledger rows, the two balance updates, and │
 * │ the status change either ALL commit together, or - if anything throws   │
 * │ at any point - they ALL roll back and the database is exactly as it was.│
 * │ There is no window in which the sender is debited but the receiver is   │
 * │ not credited.                                                           │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * On any validation failure (self-transfer, unknown recipient, insufficient
 * balance) we reject with a clear exception and, crucially, write NOTHING - no
 * ledger entries, no balance change, and (because the method is @Transactional
 * and Spring rolls back on any thrown RuntimeException) no transaction row
 * either. No money moved, so the database is left exactly as it was
 * (Section 3.2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Execute a transfer of {@code amount} from the authenticated sender to
     * the user identified by {@code receiverEmail}.
     *
     * @Transactional wraps the ENTIRE method so every write below is part of
     * one atomic unit (see the class javadoc). If any step throws, the whole
     * thing rolls back.
     *
     * @param senderUserId   the authenticated sender's user id (from their JWT)
     * @param receiverEmail  the receiver's account email (from the request body)
     * @param amount         the rupee amount to transfer (already bean-validated positive)
     * @param idempotencyKey the client-generated Idempotency-Key header value
     */
    @Transactional
    public TransferResponse transfer(Long senderUserId, String receiverEmail,
                                     BigDecimal amount, String idempotencyKey) {

        // --- Step 1: idempotency short-circuit -----------------------------
        // If we've already processed this key, return the ORIGINAL outcome
        // instead of moving money a second time (PROJECT_SPEC.md Section 3.1).
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transaction txn = existing.get();
            log.info("Idempotency-Key {} already processed -> returning existing transfer txn id={}",
                    idempotencyKey, txn.getId());
            // Recompute the sender's current balance for the echo. The sender
            // wallet on the original txn is the source of truth.
            BigDecimal senderBalance = txn.getSenderWallet() != null
                    ? txn.getSenderWallet().getBalance()
                    : BigDecimal.ZERO;
            return TransferResponse.from(txn, receiverEmail, senderBalance);
        }

        // --- Step 2: resolve + validate sender and receiver ----------------
        // Sender's wallet (from the authenticated user id - never from input).
        Wallet senderWallet = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for the current user"));

        // Receiver: look up by email. We normalise the email to lower-case/
        // trimmed to avoid "Bob@x.com" vs "bob@x.com" misses. If there's no
        // such user we throw InvalidTransferException (a 400 that does NOT
        // confirm whether the email exists - see that exception's javadoc).
        String normalisedEmail = receiverEmail == null ? "" : receiverEmail.trim().toLowerCase();
        User receiver = userRepository.findByEmail(normalisedEmail)
                .orElseThrow(() -> new InvalidTransferException(
                        "Transfer could not be completed to the specified recipient"));

        Wallet receiverWallet = walletRepository.findByUserId(receiver.getId())
                .orElseThrow(() -> new InvalidTransferException(
                        "Transfer could not be completed to the specified recipient"));

        // Can't send money to yourself. Comparing wallet ids is the precise
        // check (two different users can never share a wallet id).
        if (senderWallet.getId().equals(receiverWallet.getId())) {
            throw new InvalidTransferException("You cannot transfer money to yourself");
        }

        // --- Step 3: THE balance check -------------------------------------
        // Does the sender have enough? Use BigDecimal.compareTo, NEVER
        // equals(): equals() also compares SCALE, so new BigDecimal("100.0")
        // .equals("100.00") is false, whereas compareTo treats them as equal.
        // compareTo(...) < 0 means senderBalance is strictly LESS than amount.
        if (senderWallet.getBalance().compareTo(amount) < 0) {
            // Reject WITHOUT writing anything. A subtle but important point:
            // this whole method is @Transactional, and Spring rolls back the
            // transaction on ANY RuntimeException. So there is no point trying
            // to persist a "FAILED" audit row here and then throwing - the
            // throw would roll that very row back, and it would silently
            // vanish. (If we ever genuinely WANT a durable record of failed
            // attempts, it must be written in a SEPARATE transaction, e.g. via
            // a REQUIRES_NEW method or an application event handled after
            // rollback - deliberately out of scope here.) We simply reject:
            // no money moved, no ledger entry, nothing persisted.
            log.info("Transfer rejected (insufficient balance): sender={} balance={} amount={}",
                    senderWallet.getId(), senderWallet.getBalance(), amount);

            throw new InsufficientBalanceException("Insufficient wallet balance for this transfer");
        }

        // --- Step 4: create the transaction (CREATED) ----------------------
        // We start it CREATED and then, once the ledger is written and
        // balances updated, transition it to SUCCESS via the state machine.
        Transaction txn = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.CREATED)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(amount)
                .currency(senderWallet.getCurrency())
                .build();
        txn = transactionRepository.save(txn);

        // --- Step 5: double-entry ledger + both cached balances ------------
        // Compute both new balances up front from the CURRENT cached values.
        BigDecimal newSenderBalance = senderWallet.getBalance().subtract(amount);
        BigDecimal newReceiverBalance = receiverWallet.getBalance().add(amount);

        // DEBIT the sender: money leaving. amount is always the positive
        // magnitude; the DEBIT type is what marks it as an outflow. We record
        // balance_after so the row doubles as a statement line.
        LedgerEntry debit = LedgerEntry.builder()
                .transaction(txn)
                .wallet(senderWallet)
                .entryType(EntryType.DEBIT)
                .amount(amount)
                .balanceAfter(newSenderBalance)
                .build();

        // CREDIT the receiver: money arriving. Equal and opposite to the
        // DEBIT above - together they NET TO ZERO (the double-entry invariant).
        LedgerEntry credit = LedgerEntry.builder()
                .transaction(txn)
                .wallet(receiverWallet)
                .entryType(EntryType.CREDIT)
                .amount(amount)
                .balanceAfter(newReceiverBalance)
                .build();

        // Persist both entries. saveAll keeps them together and makes the
        // "two entries, one transaction" intent explicit.
        ledgerEntryRepository.saveAll(List.of(debit, credit));

        // Update BOTH cached balances to match the ledger we just wrote.
        // Because we're inside one @Transactional, the ledger rows and these
        // two balances can never diverge - they commit as a single unit.
        senderWallet.setBalance(newSenderBalance);
        receiverWallet.setBalance(newReceiverBalance);
        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        // --- Step 6: promote CREATED -> SUCCESS (guarded) ------------------
        TransactionStateMachine.assertCanTransition(txn.getStatus(), TransactionStatus.SUCCESS);
        txn.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(txn);

        log.info("Transfer SUCCESS: txn={} from wallet={} to wallet={} amount={} newSenderBalance={}",
                txn.getId(), senderWallet.getId(), receiverWallet.getId(), amount, newSenderBalance);

        return TransferResponse.from(txn, receiver.getEmail(), newSenderBalance);
    }
}
