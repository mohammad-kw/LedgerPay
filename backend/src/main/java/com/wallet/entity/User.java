package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Maps to the "users" table from PROJECT_SPEC.md Section 4.
 *
 * This is a person who has registered an account. Every User will get
 * exactly one {@link Wallet} auto-created for them at registration time
 * (that logic comes in a later phase - for now this is just the shape of
 * the data).
 *
 * ------------------------------------------------------------------
 * Why these particular Lombok annotations, and not just @Data?
 * ------------------------------------------------------------------
 * @Getter / @Setter
 *      Generates the boilerplate accessor methods Hibernate and our future
 *      service code need, without extra baggage.
 *
 * @NoArgsConstructor
 *      REQUIRED by JPA. Hibernate creates entity instances via reflection
 *      (it calls `new User()` internally, then fills in the fields) - it
 *      cannot use a constructor that takes arguments.
 *
 * @AllArgsConstructor + @Builder
 *      Gives us a fluent, readable way to construct instances in our own
 *      code later, e.g. `User.builder().name("Asha").email(...).build()`,
 *      which is especially handy in unit tests.
 *
 * We deliberately do NOT use Lombok's @Data (which would bundle in
 * @ToString, @EqualsAndHashCode, and @RequiredArgsConstructor too) and we do
 * NOT hand-write equals()/hashCode() ourselves either. This is a common JPA
 * gotcha worth knowing for interviews:
 *   - Before an entity is saved, its `id` is null. If equals()/hashCode()
 *     were based on `id`, two different brand-new (transient) User objects
 *     would incorrectly report themselves as "equal" (null == null).
 *   - Entities are frequently wrapped in lazy-loading proxies by Hibernate.
 *     Comparing "by value" across different proxies/persistence sessions
 *     is a classic source of subtle bugs (e.g. an object mysteriously
 *     "vanishing" from a HashSet after its hashCode changes once an id is
 *     assigned on insert).
 *   - So here we simply keep Java's default identity-based equals/hashCode
 *     (two objects are equal only if they are the exact same instance in
 *     memory), which is the safest default until/unless we have a specific
 *     reason to change it.
 * The same reasoning applies to every other entity in this package, so we
 * won't repeat this full explanation again - just this note.
 *
 * We also skip @ToString deliberately: entities can be linked to other
 * entities (e.g. Wallet -> User), and an auto-generated toString() that
 * touches a lazy-loaded association can trigger extra, unexpected SQL
 * queries or even exceptions if the database session is already closed.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /**
     * Primary key. GenerationType.IDENTITY delegates id generation to the
     * database's own AUTO_INCREMENT column (exactly what Section 4's schema
     * declares: "id BIGINT PRIMARY KEY AUTO_INCREMENT").
     *
     * Trade-off worth knowing: IDENTITY forces Hibernate to insert one row
     * at a time (it must ask the DB for the generated key immediately
     * after each insert), which disables JDBC statement batching. An
     * alternative is a DB SEQUENCE, which allows batching but is not
     * natively how MySQL's AUTO_INCREMENT works. Since our schema is
     * explicitly AUTO_INCREMENT and this project's transaction volume is
     * small (portfolio/demo scale, not high-throughput production), we
     * accept that trade-off for simplicity and schema-correctness.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** VARCHAR(100) NOT NULL - the user's display name. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * VARCHAR(150) UNIQUE NOT NULL - also doubles as the login username.
     * `unique = true` tells Hibernate to add a UNIQUE constraint when it
     * generates the DDL, matching the schema exactly.
     */
    @Column(name = "email", nullable = false, length = 150, unique = true)
    private String email;

    /**
     * VARCHAR(255) NOT NULL - NEVER a plain-text password.
     * This column stores the output of a one-way BCrypt hash (see
     * PROJECT_SPEC.md Section 7). BCrypt output strings are ~60 characters,
     * so 255 leaves comfortable headroom. The actual hashing logic (via
     * Spring Security's BCryptPasswordEncoder) is added in a later phase -
     * this field just reserves the column.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** VARCHAR(15), nullable - optional phone number, no NOT NULL in the schema. */
    @Column(name = "phone", length = 15)
    private String phone;

    /**
     * VARCHAR(20) NOT NULL - the user's authorization role (USER or ADMIN).
     *
     * @Enumerated(EnumType.STRING) stores the enum's NAME ("USER"/"ADMIN") as
     * text rather than its ordinal position (0/1). This is the safe,
     * interview-defensible choice: ordinals silently break if the enum's
     * declaration order ever changes or a value is inserted in the middle
     * (every existing row would suddenly mean a different role), whereas the
     * string name is stable and human-readable in the database.
     *
     * @Builder.Default ensures that when we build a User without specifying a
     * role (the normal registration path), it defaults to USER rather than
     * null - Lombok's @Builder would otherwise ignore the field initializer.
     * Admins are never created through self-registration; they're seeded (see
     * AdminSeeder) or promoted directly in the database.
     *
     * columnDefinition sets a DB-level DEFAULT 'USER' as a second safety net:
     * when Hibernate's ddl-auto=update ADDS this column to a table that
     * already has rows, those pre-existing rows are backfilled with 'USER'
     * rather than an arbitrary value - the @Builder.Default only covers NEW
     * Java objects, not rows already in the database.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) DEFAULT 'USER'")
    @Builder.Default
    private Role role = Role.USER;

    /**
     * TIMESTAMP DEFAULT CURRENT_TIMESTAMP.
     *
     * Instead of relying on the database's own "DEFAULT CURRENT_TIMESTAMP"
     * clause, we use Hibernate's @CreationTimestamp so the value is set in
     * Java at the moment the row is inserted. Why prefer this over the DB
     * default?
     *   1. Portability: PROJECT_SPEC.md Section 2 says the deployed demo
     *      might run on MySQL OR PostgreSQL depending on host availability.
     *      Relying on Hibernate to set the timestamp means identical
     *      behaviour on both databases, instead of depending on
     *      vendor-specific DDL defaults.
     *   2. It shows up immediately on the in-memory Java object right after
     *      save(), with no need to re-fetch the row from the DB to see it.
     *
     * `updatable = false` ensures this column is only ever set once, at
     * insert time, and Hibernate will never include it in an UPDATE
     * statement later.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
