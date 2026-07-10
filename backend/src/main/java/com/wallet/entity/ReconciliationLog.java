package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to the "reconciliation_logs" table from PROJECT_SPEC.md Section 4.
 *
 * Represents one run of the reconciliation job described in Section 3.5 -
 * the scheduled (or manually-triggered via POST /api/admin/reconciliation/
 * run) process that compares our local "successful" transactions against
 * Razorpay's own records for the same date range, and flags any
 * mismatches. This is called out in the spec as "the biggest
 * differentiator" versus other junior portfolio projects, so this table is
 * effectively the audit trail proving that differentiator actually works.
 *
 * One row = one execution of the job (e.g. "the run that happened for
 * 2026-07-08"), summarizing how many transactions were checked and how many
 * mismatches were found, plus the full details as JSON for drill-down.
 *
 * See {@link User} for why this class uses @Getter/@Setter/
 * @NoArgsConstructor/@AllArgsConstructor/@Builder instead of Lombok's
 * @Data, and why @ToString/@EqualsAndHashCode are skipped.
 */
@Entity
@Table(name = "reconciliation_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * DATE NOT NULL - which calendar day this reconciliation run was
     * checking (e.g. "compare everything that happened on 2026-07-07").
     * LocalDate (no time-of-day component) matches the SQL DATE column
     * type exactly, as opposed to LocalDateTime/TIMESTAMP used everywhere
     * else in this project.
     */
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    /** INT NOT NULL - how many of our local transactions were compared against Razorpay's records in this run. */
    @Column(name = "total_checked", nullable = false)
    private Integer totalChecked;

    /** INT NOT NULL - how many of those showed a mismatch (e.g. we think SUCCESS, Razorpay says FAILED, or vice versa). Ideally always 0! */
    @Column(name = "mismatches_found", nullable = false)
    private Integer mismatchesFound;

    /**
     * TEXT, nullable - a JSON array with the specifics of each mismatch
     * found (e.g. which transaction id, what we had vs what Razorpay had).
     * @Lob for the same "this can be a large blob of text" reason as
     * WebhookEvent.payloadJson. Nullable because a clean run with zero
     * mismatches may have nothing to report here.
     */
    @Lob
    @Column(name = "mismatch_details_json")
    private String mismatchDetailsJson;

    /**
     * VARCHAR(20) NOT NULL - COMPLETED or FAILED (e.g. did the job run to
     * completion, or did calling Razorpay's API itself error out?). This is
     * intentionally a plain String rather than a dedicated Java enum like
     * TransactionStatus/EntryType: it's a small, self-contained two-value
     * status specific to this one table with no shared meaning elsewhere
     * in the domain, so a lightweight String keeps things simple without
     * losing any clarity. (A dedicated enum would also be a perfectly
     * reasonable choice here - this is a judgment call, not a hard rule.)
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** TIMESTAMP DEFAULT CURRENT_TIMESTAMP - see User.createdAt for why we use Hibernate's @CreationTimestamp instead of a DB-level default. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
