package com.wallet.dto;

import com.wallet.entity.EntryType;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The JSON response body for a single row in GET /api/wallet/transactions
 * (PROJECT_SPEC.md Section 5). See RegisterResponse's javadoc for why this
 * is a plain Java `record`, and BalanceResponse's javadoc for why we map
 * entities to a DTO instead of returning the {@link Transaction} entity
 * directly.
 *
 * The interesting field here is {@code direction}. The same Transaction row
 * means different things depending on WHO is looking at it: a TRANSFER from
 * Alice to Bob is money LEAVING Alice's wallet (a DEBIT, shown in red/
 * negative) but money ARRIVING in Bob's wallet (a CREDIT, shown in green/
 * positive). Rather than make the frontend figure that out, we compute it
 * server-side in {@link #from(Transaction, Long)} relative to the wallet
 * that's currently viewing the list, so the UI can simply trust it.
 */
public record TransactionResponse(

        Long id,
        TransactionType type,
        TransactionStatus status,

        /** Always the positive transaction amount (see Transaction.amount). Pair it with `direction` to know the sign. */
        BigDecimal amount,
        String currency,

        /**
         * CREDIT if this transaction increased the viewing wallet's balance
         * (money in), DEBIT if it decreased it (money out). Computed
         * relative to the current user's wallet - see the class javadoc.
         */
        EntryType direction,

        /** The Razorpay order id, if this was a top-up (null otherwise). Useful for support/debugging and later reconciliation. */
        String razorpayOrderId,

        LocalDateTime createdAt
) {

    /**
     * Maps a {@link Transaction} entity into this DTO, computing {@code
     * direction} from the perspective of {@code viewingWalletId} (the wallet
     * of the currently-logged-in user making the request).
     *
     * The rule: if OUR wallet is the receiver, money came IN -> CREDIT;
     * otherwise (we're the sender) money went OUT -> DEBIT. We read the
     * receiver's id via the LAZY association, which is safe here because
     * this mapping runs inside the service method's transaction (the
     * Hibernate session is still open), before the data is serialized to
     * JSON.
     */
    public static TransactionResponse from(Transaction txn, Long viewingWalletId) {
        boolean isReceiver = txn.getReceiverWallet() != null
                && viewingWalletId.equals(txn.getReceiverWallet().getId());
        EntryType direction = isReceiver ? EntryType.CREDIT : EntryType.DEBIT;

        return new TransactionResponse(
                txn.getId(),
                txn.getType(),
                txn.getStatus(),
                txn.getAmount(),
                txn.getCurrency(),
                direction,
                txn.getRazorpayOrderId(),
                txn.getCreatedAt()
        );
    }
}
