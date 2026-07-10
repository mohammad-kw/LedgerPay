package com.wallet.entity;

/**
 * Backs the "entry_type" VARCHAR(10) column on the "ledger_entries" table
 * (PROJECT_SPEC.md Section 4), and represents one half of a double-entry
 * bookkeeping pair (Section 3.2).
 *
 * Every transaction that moves money produces at least two LedgerEntry
 * rows that must always net to zero:
 *   - DEBIT  = money leaving a wallet (the wallet's balance goes down)
 *   - CREDIT = money entering a wallet (the wallet's balance goes up)
 *
 * Example - a TRANSFER of 100.00 INR from Alice to Bob produces exactly
 * two ledger rows, both linked to the same transaction_id:
 *   1. DEBIT  100.00 on Alice's wallet_id
 *   2. CREDIT 100.00 on Bob's wallet_id
 * Sum of amounts (treating DEBIT as negative, CREDIT as positive) = 0.
 * This "must always net to zero" invariant is exactly what makes the
 * system auditable and is a prime candidate for a unit test in Phase 4.
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
