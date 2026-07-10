package com.wallet.dto;

import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;

import java.math.BigDecimal;

/**
 * The JSON response body for POST /api/wallet/topup/mock - the DEV-ONLY
 * "instant" top-up that bypasses Razorpay (see MockTopUpService and
 * MockTopUpController for the full why/how).
 *
 * Unlike the real {@link TopUpInitiateResponse} (which only creates a Razorpay
 * order and returns details for Checkout, with the wallet credited LATER by
 * the webhook), the mock credits the wallet immediately and synchronously. So
 * this response reflects a COMPLETED top-up: the transaction is already SUCCESS
 * and {@code balanceAfter} is the wallet's new, post-credit balance - handy for
 * the UI to update the displayed balance instantly.
 *
 * See RegisterResponse's javadoc for why this is a plain {@code record}, and
 * BalanceResponse's for why we return a purpose-built DTO instead of the raw
 * {@link Transaction} entity.
 */
public record MockTopUpResponse(

        /** Our transaction row's id (status SUCCESS on the happy path). */
        Long transactionId,

        /** Final status of the top-up - SUCCESS once the wallet was credited. */
        TransactionStatus status,

        /** The amount credited (positive, in rupees). */
        BigDecimal amount,

        String currency,

        /** The wallet's balance AFTER the mock credit committed. */
        BigDecimal balanceAfter
) {

    /** Build the response from the persisted transaction and the new balance computed by the service. */
    public static MockTopUpResponse from(Transaction txn, BigDecimal balanceAfter) {
        return new MockTopUpResponse(
                txn.getId(),
                txn.getStatus(),
                txn.getAmount(),
                txn.getCurrency(),
                balanceAfter
        );
    }
}
