package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
 * Maps to the "wallets" table from PROJECT_SPEC.md Section 4.
 *
 * Every {@link User} has exactly one Wallet (created automatically at
 * registration time - that logic is a later phase). A wallet is where a
 * user's money "lives" inside our system.
 *
 * IMPORTANT concept (see PROJECT_SPEC.md Section 3.2 - Double-entry
 * ledger): the {@code balance} field below is a CACHED, denormalized
 * number. It is NOT the source of truth. The real source of truth is the
 * sum of all {@link LedgerEntry} rows for this wallet. We store balance
 * here too purely as a performance shortcut (so "what's my balance?" is a
 * single indexed row read instead of a SUM(...) over potentially thousands
 * of ledger rows). Whenever business logic is added in a later phase, EVERY
 * balance change must happen by (a) writing new ledger entries AND (b)
 * updating this cached column in the same database transaction - never (b)
 * alone. That is exactly why "balance" being directly editable is called
 * out in the spec as something to avoid doing carelessly.
 *
 * See {@link User} for why we use @Getter/@Setter/@NoArgsConstructor/
 * @AllArgsConstructor/@Builder here instead of Lombok's @Data, and why we
 * skip @ToString/@EqualsAndHashCode.
 */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning side of a one-to-one relationship to {@link User}.
     * "Owning side" means: this is the entity/table that actually holds the
     * foreign key column (wallets.user_id) - matching
     * "FOREIGN KEY (user_id) REFERENCES users(id)" in the schema exactly.
     *
     * fetch = FetchType.LAZY: don't load the related User row from the
     * database until someone actually calls wallet.getUser(). Without this,
     * @OneToOne defaults to FetchType.EAGER per the JPA spec, meaning every
     * single time we load a Wallet for any reason, Hibernate would also
     * silently run a second query (or a JOIN) to fetch its User - wasted
     * work if we only needed the balance.
     *
     * optional = false: matches "user_id BIGINT NOT NULL" (a wallet can
     * never exist without a user). This also happens to be what allows
     * Hibernate to honor the LAZY hint efficiently for a @OneToOne: because
     * the FK can never be null, Hibernate can safely hand back a lazy proxy
     * immediately without needing an extra existence-check query. (If this
     * were optional = true, most JPA providers would quietly ignore LAZY
     * and fetch eagerly anyway, unless bytecode enhancement is configured -
     * a subtle but well-known JPA gotcha worth remembering.)
     *
     * Note this relationship is deliberately UNIDIRECTIONAL (Wallet -> User
     * only). We do NOT add a matching "@OneToOne(mappedBy = "user") Wallet
     * wallet;" field on User. If future code needs "find this user's
     * wallet", the idiomatic Spring Data JPA approach is a repository
     * method like WalletRepository.findByUserId(userId) rather than
     * object-graph navigation - this avoids extra serialization/lazy
     * loading complexity for a relationship we don't yet need to traverse
     * backwards.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * DECIMAL(15,2) NOT NULL DEFAULT 0.00 - the cached wallet balance.
     * precision = 15, scale = 2 tells Hibernate to generate exactly
     * DECIMAL(15,2) in DDL, and BigDecimal (never float/double!) is used
     * because floating-point binary types cannot represent decimal
     * fractions like 0.10 exactly, which is unacceptable for money.
     *
     * @Builder.Default is required here alongside the field initializer:
     * Lombok's @Builder generates a completely separate internal Builder
     * class with its own fields, which do NOT automatically inherit this
     * class's field initializers. Without @Builder.Default, calling
     * Wallet.builder().user(someUser).build() would silently produce
     * balance = null instead of 0.00. This is one of the most common
     * Lombok + @Builder mistakes, so it's worth remembering.
     */
    @Builder.Default
    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /** VARCHAR(3) NOT NULL DEFAULT 'INR'. Same @Builder.Default reasoning as balance above. */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /** TIMESTAMP DEFAULT CURRENT_TIMESTAMP - set once, automatically, at insert time. See User.createdAt for why we use Hibernate's @CreationTimestamp instead of a DB-level default. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * TIMESTAMP DEFAULT CURRENT_TIMESTAMP - intended to change every time
     * the row changes (e.g. every time the balance is updated).
     * @UpdateTimestamp makes Hibernate automatically set this field to
     * "now" immediately before every UPDATE statement it issues for this
     * entity - the Hibernate-idiomatic, database-portable equivalent of
     * MySQL's "ON UPDATE CURRENT_TIMESTAMP" clause (which PostgreSQL, our
     * possible deployment fallback per Section 2, does not even support
     * natively).
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
