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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to the "transactions" table from PROJECT_SPEC.md Section 4.
 *
 * A Transaction is a record of money movement: a top-up (Razorpay -> wallet),
 * a transfer (wallet -> wallet), or a withdrawal (wallet -> outside). It is
 * the "header" row; the actual balance-affecting detail lives in the
 * {@link LedgerEntry} rows linked to it (Section 3.2 - double-entry ledger).
 *
 * See {@link User} for why this class uses @Getter/@Setter/
 * @NoArgsConstructor/@AllArgsConstructor/@Builder instead of Lombok's
 * @Data, and why @ToString/@EqualsAndHashCode are skipped.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * VARCHAR(100) UNIQUE NOT NULL - the client-generated UUID used for
     * idempotency (PROJECT_SPEC.md Section 3.1). The `unique = true`
     * constraint is what actually enforces "never process the same request
     * twice" at the database level: if service-layer code ever tries to
     * insert a second Transaction row with an idempotency_key that already
     * exists, the database itself rejects it with a constraint violation,
     * as a final safety net even if an application-level check were ever
     * accidentally skipped or race-conditioned.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    /**
     * VARCHAR(20) NOT NULL, stored as its plain enum name string (e.g.
     * "TOPUP") via EnumType.STRING - see the note on `status` below for why
     * STRING is used instead of the default ORDINAL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    /**
     * VARCHAR(20) NOT NULL - the current state in the transaction state
     * machine (see {@link TransactionStatus} for the full diagram).
     *
     * @Enumerated(EnumType.STRING) tells Hibernate to store the enum's
     * *name* ("CREATED", "PENDING", ...) as text in the column, matching
     * the schema's VARCHAR(20). This is deliberately chosen over the
     * default, EnumType.ORDINAL (which stores 0, 1, 2... based on
     * declaration order), for a very concrete reason: if a new status were
     * ever inserted in the middle of the enum declaration later (e.g.
     * adding a new status between CREATED and PENDING), ORDINAL storage
     * would silently shift the meaning of every previously stored number
     * already sitting in the database - corrupting old data with no error
     * raised. STRING storage is immune to that entire class of bug, at the
     * minor cost of using a few more bytes per row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    /**
     * sender_wallet_id BIGINT, nullable (null for TOPUP - money has no
     * internal sender, it comes from Razorpay/outside).
     *
     * fetch = FetchType.LAZY: same reasoning as Wallet.user in Wallet.java
     * - don't pull the whole Wallet row (and transitively, on access, its
     * User) just because we loaded a Transaction. Unlike Wallet.user
     * though, @ManyToOne already defaults to FetchType.EAGER per the JPA
     * spec too, so we again override it explicitly to LAZY here for the
     * same performance reason.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_wallet_id", nullable = true)
    private Wallet senderWallet;

    /** receiver_wallet_id BIGINT, nullable (null for WITHDRAWAL - money leaves our system entirely). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_wallet_id", nullable = true)
    private Wallet receiverWallet;

    /** DECIMAL(15,2) NOT NULL. BigDecimal for exact decimal arithmetic - see Wallet.balance for why never float/double for money. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** VARCHAR(3) NOT NULL DEFAULT 'INR'. See Wallet.currency for the @Builder.Default explanation - the same reasoning applies here. */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /**
     * VARCHAR(100), nullable - populated once we call Razorpay's Orders API
     * to create an order for a TOPUP (a later phase). Null for
     * TRANSFER/WITHDRAWAL since those never touch Razorpay.
     */
    @Column(name = "razorpay_order_id", length = 100)
    private String razorpayOrderId;

    /**
     * VARCHAR(100), nullable - populated once Razorpay's webhook confirms
     * an actual payment was made against the order above (a later phase).
     */
    @Column(name = "razorpay_payment_id", length = 100)
    private String razorpayPaymentId;

    /** TIMESTAMP DEFAULT CURRENT_TIMESTAMP - see User.createdAt for why we use Hibernate's @CreationTimestamp instead of a DB-level default. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** TIMESTAMP DEFAULT CURRENT_TIMESTAMP - see Wallet.updatedAt for why we use Hibernate's @UpdateTimestamp instead of a DB-level "ON UPDATE" clause. Will change every time `status` transitions (e.g. PENDING -> SUCCESS). */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
