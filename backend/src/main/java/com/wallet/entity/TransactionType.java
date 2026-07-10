package com.wallet.entity;

/**
 * Backs the "type" VARCHAR(20) column on the "transactions" table
 * (PROJECT_SPEC.md Section 4). Using a real Java enum instead of a raw
 * String for this column means:
 *   - Typos like "TOPP" instead of "TOPUP" become impossible - they simply
 *     won't compile.
 *   - Every valid value is documented in exactly one place (here).
 *   - We get exhaustive `switch` statements later (the compiler/IDE can
 *     warn us if a new type is added but a switch elsewhere forgot to
 *     handle it).
 *
 * How each type uses the sender/receiver wallet columns on Transaction
 * (see Transaction.java for the actual nullable foreign keys):
 *   - TOPUP:      senderWallet = null,        receiverWallet = the user adding money (money enters our system from Razorpay)
 *   - TRANSFER:   senderWallet = sender,       receiverWallet = recipient (money moves between two wallets we hold)
 *   - WITHDRAWAL: senderWallet = the user,     receiverWallet = null (money leaves our system)
 */
public enum TransactionType {
    TOPUP,
    TRANSFER,
    WITHDRAWAL
}
