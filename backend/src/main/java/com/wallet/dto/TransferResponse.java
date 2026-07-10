package com.wallet.dto;

import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;

import java.math.BigDecimal;

/**
 * The JSON response body for POST /api/wallet/transfer (PROJECT_SPEC.md
 * Section 5). See RegisterResponse's javadoc for why this is a plain `record`,
 * and BalanceResponse's javadoc for why we return a purpose-built DTO instead
 * of the raw {@link Transaction} entity.
 *
 * It echoes the essentials of the completed transfer plus the sender's NEW
 * cached balance, so the frontend can update the displayed balance instantly
 * from the response without needing a second GET /api/wallet/balance call.
 */
public record TransferResponse(

        /** Our transaction row's id (status SUCCESS on the happy path). */
        Long transactionId,

        /** Final status of the transfer - SUCCESS when the money moved, FAILED otherwise. */
        TransactionStatus status,

        /** The receiver's email, echoed back for the UI to confirm who was paid. */
        String receiverEmail,

        /** The amount transferred (positive, in rupees). */
        BigDecimal amount,

        String currency,

        /**
         * The SENDER's wallet balance AFTER the transfer committed. Handy for
         * the UI to reflect the new balance immediately. (The receiver's
         * balance isn't included - it belongs to a different user and this
         * response goes to the sender.)
         */
        BigDecimal senderBalanceAfter
) {

    /**
     * Build the response from the persisted transaction, the receiver's email
     * (which the entity references only by wallet/user association), and the
     * sender's post-transfer balance computed by the service.
     */
    public static TransferResponse from(Transaction txn, String receiverEmail, BigDecimal senderBalanceAfter) {
        return new TransferResponse(
                txn.getId(),
                txn.getStatus(),
                receiverEmail,
                txn.getAmount(),
                txn.getCurrency(),
                senderBalanceAfter
        );
    }
}
