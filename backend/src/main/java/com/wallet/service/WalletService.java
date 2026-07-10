package com.wallet.service;

import com.wallet.dto.BalanceResponse;
import com.wallet.dto.PageResponse;
import com.wallet.dto.TransactionResponse;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.Wallet;
import com.wallet.exception.TransactionNotFoundException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the read-only wallet endpoints in PROJECT_SPEC.md
 * Section 5: GET /api/wallet/balance and GET /api/wallet/transactions.
 * WalletController (thin HTTP layer) delegates all real work here - see
 * com.wallet.service's package-info.java for why we keep that separation.
 *
 * SECURITY MODEL (important, and worth being able to explain in an
 * interview): none of these methods accept a walletId or userId from the
 * client. They take the authenticated user's OWN id, which the controller
 * reads from the verified JWT via the SecurityContext - never from a
 * request parameter. That structurally guarantees a user can only ever see
 * THEIR OWN wallet: there is simply no input through which they could ask
 * for someone else's (this defends against "Insecure Direct Object
 * Reference" / IDOR bugs, where an app naively trusts an id from the URL).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    /** Default page size when the client doesn't specify one. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** Upper bound on page size, so a client can't request, say, 1,000,000 rows at once and exhaust memory. */
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /**
     * GET /api/wallet/balance - return the current user's cached wallet
     * balance (PROJECT_SPEC.md Section 5).
     *
     * @Transactional(readOnly = true): this only reads, never writes, so we
     * mark it read-only. That lets the JPA provider/DB skip dirty-checking
     * and can enable read-oriented optimizations, and it documents intent.
     * It also keeps the Hibernate session open for the whole method, which
     * matters more in the transaction-list method below (for LAZY access).
     *
     * @param userId the authenticated user's id (from their JWT, supplied by the controller)
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(Long userId) {
        Wallet wallet = requireWalletForUser(userId);
        return new BalanceResponse(wallet.getId(), wallet.getBalance(), wallet.getCurrency());
    }

    /**
     * GET /api/wallet/transactions?page=&status= - return one page of the
     * current user's transaction history, newest first, optionally filtered
     * by status (PROJECT_SPEC.md Section 5).
     *
     * @param userId the authenticated user's id (from their JWT)
     * @param page   zero-based page index (negative values are clamped to 0)
     * @param size   requested page size (clamped to 1..MAX_PAGE_SIZE)
     * @param status optional status filter, or null for "all statuses"
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getTransactions(
            Long userId, int page, int size, TransactionStatus status) {

        Wallet wallet = requireWalletForUser(userId);

        // Sort newest-first by createdAt. Doing the sort in the query (via
        // Pageable) means the DATABASE orders and paginates the rows - we
        // never load the whole history into memory just to sort it here.
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<Transaction> results =
                transactionRepository.findForWallet(wallet.getId(), status, pageable);

        // Map each Transaction entity to a DTO, computing debit/credit
        // direction relative to THIS wallet (see TransactionResponse.from),
        // and wrap the whole page in our stable PageResponse envelope.
        return PageResponse.from(results, txn -> TransactionResponse.from(txn, wallet.getId()));
    }

    /**
     * GET /api/wallet/transactions/{id} - return ONE transaction belonging to
     * the current user (PROJECT_SPEC.md Section 5).
     *
     * IDOR-safe by construction: we load the user's own wallet first, then
     * only return the transaction if that wallet is either its sender or its
     * receiver. If the id doesn't exist, OR exists but belongs to someone
     * else, we throw the SAME TransactionNotFoundException (-> 404) so we
     * never leak whether an id someone else owns exists (see that exception's
     * javadoc).
     *
     * @param userId        the authenticated user's id (from their JWT)
     * @param transactionId the id from the URL path
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(Long userId, Long transactionId) {
        Wallet wallet = requireWalletForUser(userId);

        Transaction txn = transactionRepository.findById(transactionId)
                .filter(t -> belongsToWallet(t, wallet.getId()))
                .orElseThrow(() -> new TransactionNotFoundException(
                        "No transaction found with id " + transactionId));

        return TransactionResponse.from(txn, wallet.getId());
    }

    /** True if the wallet is either the sender or the receiver of the transaction. */
    private boolean belongsToWallet(Transaction txn, Long walletId) {
        boolean isSender = txn.getSenderWallet() != null
                && walletId.equals(txn.getSenderWallet().getId());
        boolean isReceiver = txn.getReceiverWallet() != null
                && walletId.equals(txn.getReceiverWallet().getId());
        return isSender || isReceiver;
    }

    /**
     * Shared helper: load the wallet belonging to the given user, or throw
     * a clear WalletNotFoundException (-> 404) if somehow none exists. Both
     * public methods start by calling this, so the "which wallet am I
     * allowed to touch?" decision lives in exactly one place.
     */
    private Wallet requireWalletForUser(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(
                        "No wallet found for the current user"));
    }

    /** Clamp a requested page size into the sane range [1, MAX_PAGE_SIZE], defaulting when non-positive. */
    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
