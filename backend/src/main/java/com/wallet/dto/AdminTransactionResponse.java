package com.wallet.dto;

import com.wallet.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the admin GLOBAL transaction feed (GET /api/admin/transactions).
 *
 * Unlike the user-facing {@code TransactionResponse}, this is a system-wide
 * view across all users, so it includes both the sender and receiver wallet
 * ids for oversight. It is still a flat, purpose-built projection - it never
 * exposes password hashes or full nested entities.
 *
 * The sender/receiver wallet ids are read here (inside a @Transactional admin
 * service method) while the Hibernate session is still open, so the LAZY
 * associations resolve without a LazyInitializationException.
 */
public record AdminTransactionResponse(
        Long id,
        String type,
        String status,
        BigDecimal amount,
        String currency,
        Long senderWalletId,
        Long receiverWalletId,
        String razorpayPaymentId,
        LocalDateTime createdAt) {

    public static AdminTransactionResponse from(Transaction t) {
        return new AdminTransactionResponse(
                t.getId(),
                t.getType().name(),
                t.getStatus().name(),
                t.getAmount(),
                t.getCurrency(),
                t.getSenderWallet() != null ? t.getSenderWallet().getId() : null,
                t.getReceiverWallet() != null ? t.getReceiverWallet().getId() : null,
                t.getRazorpayPaymentId(),
                t.getCreatedAt());
    }
}
