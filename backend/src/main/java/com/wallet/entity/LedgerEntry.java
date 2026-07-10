package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to the "ledger_entries" table from PROJECT_SPEC.md Section 4.
 *
 * This is the most important table in the whole system conceptually
 * (Section 3.2 - Double-entry ledger). Every {@link Transaction} that moves
 * money produces two (or more) LedgerEntry rows - one DEBIT and one
 * CREDIT - that must always sum to zero. These rows are the actual,
 * permanent, auditable source of truth for "why is this wallet's balance
 * what it is" - NOT the cached Wallet.balance column, which is just a fast
 * read-shortcut derived from these rows.
 *
 * Once a LedgerEntry is written, it should NEVER be updated or deleted
 * (this class intentionally has no business logic yet, but keep this
 * invariant in mind for the service layer in a later phase - ledger rows
 * are append-only, like a real accounting ledger). If a payment needs to be
 * reversed, the correct approach is to insert NEW, opposite-direction
 * entries referencing a new REVERSED transaction, not to edit history.
 *
 * See {@link User} for why this class uses @Getter/@Setter/
 * @NoArgsConstructor/@AllArgsConstructor/@Builder instead of Lombok's
 * @Data, and why @ToString/@EqualsAndHashCode are skipped.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * transaction_id BIGINT NOT NULL - links this entry back to the
     * {@link Transaction} "header" row that caused it. fetch = LAZY for the
     * same reason as every other relationship in this project (see
     * Wallet.user's javadoc) - don't pull in the whole Transaction (and
     * transitively its Wallets) just to read this entry's amount.
     *
     * nullable = false matches "NOT NULL" in the schema exactly.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** wallet_id BIGINT NOT NULL - which wallet this specific debit/credit applies to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /**
     * VARCHAR(10) NOT NULL - DEBIT or CREDIT. See {@link EntryType} for the
     * full explanation of what these mean and a worked example. Stored as
     * EnumType.STRING for the same "immune to reordering" reason explained
     * in Transaction.status's javadoc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    /**
     * DECIMAL(15,2) NOT NULL - always stored as a positive magnitude. The
     * sign/direction of the movement comes from `entryType` (DEBIT vs
     * CREDIT), not from this number being negative. This keeps "amount" as
     * an intuitive, always-positive quantity throughout the codebase and
     * avoids a whole class of sign-flipping bugs.
     */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * DECIMAL(15,2) NOT NULL - a running-balance snapshot: what this
     * wallet's balance became immediately after this entry was applied.
     * Storing this here (rather than recalculating it on the fly every
     * time) means we can show a "statement" view of a wallet's history
     * (like a real bank statement, where each line shows the balance after
     * that line) without re-summing every prior entry on every read.
     */
    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /**
     * TIMESTAMP DEFAULT CURRENT_TIMESTAMP - see User.createdAt for why we
     * use Hibernate's @CreationTimestamp instead of a DB-level default.
     * Note there is deliberately no "updated_at" here, unlike Wallet/
     * Transaction - ledger entries are append-only and are never updated
     * after creation (see the class-level javadoc above).
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
