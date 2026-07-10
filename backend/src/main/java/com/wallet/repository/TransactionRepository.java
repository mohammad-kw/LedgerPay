package com.wallet.repository;

import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Transaction}. See UserRepository's
 * javadoc for a general explanation of how these interfaces work (we
 * declare the method, Spring Data generates the implementation).
 *
 * This one backs GET /api/wallet/transactions (PROJECT_SPEC.md Section 5):
 * the paginated, optionally-status-filtered transaction history for a
 * single wallet.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Look up a transaction by its client-supplied idempotency key
     * (PROJECT_SPEC.md Section 3.1). This is the heart of idempotent
     * request handling: before creating a NEW transaction for an incoming
     * "add money"/"transfer" request, we first check whether we've already
     * seen this exact Idempotency-Key. If we have, we return the ORIGINAL
     * transaction's result instead of creating a duplicate - so a
     * double-clicked button or an auto-retried network call can never
     * charge/move money twice.
     *
     * Optional<Transaction> (not null) forces callers to handle the
     * "not seen before" case explicitly - see UserRepository.findByEmail's
     * javadoc for the reasoning.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);


    /**
     * Find the transaction we created for a given Razorpay order id.
     *
     * This is the key join between "our world" and "Razorpay's world" during
     * webhook processing (PROJECT_SPEC.md Section 3.4). When Razorpay sends a
     * payment.captured / payment.failed webhook, the payload identifies the
     * payment by its Razorpay order id (the same id we stored on the
     * Transaction back in TopUpService when we created the order). We look the
     * transaction up by that id so we know exactly which local transaction to
     * move CREATED -> SUCCESS (or -> FAILED) and which wallet to credit.
     *
     * Returns Optional because a webhook could, in principle, reference an
     * order we don't recognise (e.g. one created by a different system using
     * the same Razorpay account) - the caller must handle that "not found"
     * case rather than assume a match always exists.
     */
    Optional<Transaction> findByRazorpayOrderId(String razorpayOrderId);


    /**
     * Fetch one "page" of a wallet's transactions, newest first, optionally
     * filtered by status.
     *
     * A transaction is considered to "belong to" a wallet if that wallet is
     * EITHER the sender OR the receiver - so this single query covers
     * money-in (top-ups/incoming transfers, where we're the receiver) AND
     * money-out (outgoing transfers/withdrawals, where we're the sender).
     *
     * Why a hand-written @Query instead of a derived method name like
     * findBySenderWalletIdOrReceiverWalletId? Two reasons:
     *   1. The "sender OR receiver" condition combined with the optional
     *      status filter is far clearer written out as JPQL than encoded
     *      into a very long method name.
     *   2. The optional-filter trick "(:status IS NULL OR t.status =
     *      :status)" lets ONE method serve both "all statuses" (when the
     *      caller passes null) and "just this status" - instead of needing
     *      two separate methods. When :status is null the whole left side
     *      is true, so the status condition is effectively skipped; when
     *      it's provided, it filters normally.
     *
     * Pagination note: because senderWallet/receiverWallet are @ManyToOne
     * (to-ONE) associations, Spring/Hibernate can safely apply the LIMIT/
     * OFFSET in SQL. (The "can't paginate in the DB" warning only applies
     * to JOIN FETCH-ing to-MANY collections, which we deliberately don't do
     * here.) The Pageable also carries the sort order (see WalletService,
     * which sorts by createdAt DESC).
     *
     * @param walletId the current user's wallet id
     * @param status   a specific status to filter by, or null for "any status"
     * @param pageable which page/size/sort to return
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.senderWallet.id = :walletId OR t.receiverWallet.id = :walletId)
              AND (:status IS NULL OR t.status = :status)
            """)
    Page<Transaction> findForWallet(
            @Param("walletId") Long walletId,
            @Param("status") TransactionStatus status,
            Pageable pageable
    );

    /**
     * Fetch every TOPUP transaction created within a date/time window, for the
     * reconciliation job (PROJECT_SPEC.md Section 3.5).
     *
     * We restrict to type = TOPUP on purpose: those are the ONLY transactions
     * that have a counterpart in Razorpay's records (a top-up is the one flow
     * that actually goes through the payment gateway). TRANSFERs and
     * WITHDRAWALs are purely internal money movement - Razorpay never sees
     * them, so there is nothing on their side to reconcile against, and
     * including them would produce spurious "missing at gateway" mismatches.
     *
     * The window is compared against created_at (when we first recorded the
     * transaction). A half-open interval [from, to) is the caller's
     * responsibility to supply (see ReconciliationService), which avoids
     * double-counting a transaction that lands exactly on a day boundary.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.type = com.wallet.entity.TransactionType.TOPUP
              AND t.createdAt >= :from
              AND t.createdAt < :to
            """)
    List<Transaction> findTopupsCreatedBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // ------------------------------------------------------------------
    // Admin dashboard aggregates (PROJECT_SPEC.md: admin metrics view).
    // These are read-only reporting queries used ONLY by the admin
    // endpoints - they compute counts/sums straight in the database
    // rather than loading every row into memory.
    // ------------------------------------------------------------------

    /** How many transactions currently have the given status - powers the status breakdown chart. */
    long countByStatus(TransactionStatus status);

    /** How many transactions are of the given type (TOPUP/TRANSFER/WITHDRAWAL) - powers the type breakdown chart. */
    long countByType(TransactionType type);

    /**
     * Total SUCCESS money-movement volume of a given type since a cutoff
     * (e.g. "how much was successfully topped up today"). Returns null when
     * there are no matching rows, so callers coalesce it to zero.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.type = :type
              AND t.status = com.wallet.entity.TransactionStatus.SUCCESS
              AND t.createdAt >= :since
            """)
    java.math.BigDecimal sumSuccessfulAmountByTypeSince(
            @Param("type") TransactionType type,
            @Param("since") LocalDateTime since);

    /** Count of transactions created on/after a cutoff - powers "transactions today". */
    long countByCreatedAtAfter(LocalDateTime since);

    /**
     * The most recent transactions across ALL users (newest first), for the
     * admin global activity feed. Unlike findForWallet this is not scoped to
     * one wallet - it is an admin-only, system-wide view.
     */
    @Query("SELECT t FROM Transaction t ORDER BY t.createdAt DESC")
    List<Transaction> findRecentAcrossAllUsers(Pageable pageable);
}
