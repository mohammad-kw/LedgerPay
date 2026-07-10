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

import java.time.LocalDateTime;

/**
 * Maps to the "webhook_events" table from PROJECT_SPEC.md Section 4.
 *
 * Every single webhook call Razorpay makes to our
 * POST /api/webhooks/razorpay endpoint (a later phase) gets stored here
 * FIRST, as-is, before any business logic touches it. This directly
 * supports two requirements from the spec:
 *
 *   1. Section 3.4 (Webhooks as source of truth): we must verify the
 *      HMAC-SHA256 signature before trusting a webhook. Storing
 *      `signatureVerified` alongside the raw payload means we have a
 *      permanent audit trail of every attempt, including rejected/forged
 *      ones - useful for both debugging and demonstrating security
 *      awareness in an interview.
 *
 *   2. Section 3.4 + Phase 3 checklist ("Handle duplicate webhook
 *      delivery - Razorpay may send the same event more than once"):
 *      `razorpayEventId` is UNIQUE, so if Razorpay retries the same event
 *      (which webhook providers commonly do, to guarantee at-least-once
 *      delivery), attempting to insert it again fails on the unique
 *      constraint, and the service layer (later phase) can recognize
 *      "I've already seen this event" and simply skip re-processing it -
 *      the webhook equivalent of the Idempotency-Key pattern used for
 *      top-up/transfer requests.
 *
 * See {@link User} for why this class uses @Getter/@Setter/
 * @NoArgsConstructor/@AllArgsConstructor/@Builder instead of Lombok's
 * @Data, and why @ToString/@EqualsAndHashCode are skipped (doubly
 * important here since `payloadJson` could be large).
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * VARCHAR(100) UNIQUE NOT NULL - the unique event ID Razorpay assigns
     * to this specific webhook delivery. This is what the unique
     * constraint keys off of to detect and safely ignore duplicate
     * deliveries (see class javadoc above).
     */
    @Column(name = "razorpay_event_id", nullable = false, unique = true, length = 100)
    private String razorpayEventId;

    /**
     * VARCHAR(50) NOT NULL - what kind of event this is, e.g.
     * "payment.captured" or "payment.failed" (Razorpay's own event-type
     * naming, straight from their payload - not one of our own Java enums,
     * since we don't control or exhaustively know Razorpay's event type
     * list up front).
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * TEXT NOT NULL - the complete, raw, untouched JSON body Razorpay sent
     * us. @Lob (large object) maps this String to the database's TEXT/CLOB
     * type rather than a small VARCHAR, since webhook payloads can be
     * fairly large. Keeping the full raw payload (rather than just the
     * fields we think we need right now) means that if we discover a bug
     * later, or need a field we didn't originally parse out, we can always
     * go back and re-process this stored payload - nothing is ever lost.
     */
    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    /**
     * BOOLEAN NOT NULL DEFAULT FALSE - true only after we successfully
     * verify the X-Razorpay-Signature header (HMAC-SHA256, Section 3.4)
     * against this exact payload. Business logic in a later phase must
     * refuse to act on any event where this is false.
     */
    @Builder.Default
    @Column(name = "signature_verified", nullable = false)
    private Boolean signatureVerified = false;

    /**
     * BOOLEAN NOT NULL DEFAULT FALSE - true once our own application logic
     * has finished acting on this event (e.g. crediting a wallet after a
     * "payment.captured" event). Separate from signatureVerified because
     * an event can be verified as authentic but not yet (or not
     * successfully) processed.
     */
    @Builder.Default
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    /**
     * TIMESTAMP DEFAULT CURRENT_TIMESTAMP - when our server received this
     * webhook call. See User.createdAt for why we use Hibernate's
     * @CreationTimestamp instead of a DB-level default.
     */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    /**
     * TIMESTAMP, nullable - when we finished processing this event. Unlike
     * the other timestamp fields in this project, this one is NOT
     * auto-managed by a Hibernate annotation (there's no
     * "@SomethingTimestamp" that means "set this only when a specific flag
     * flips to true") - it will be set explicitly in application code, in a
     * later phase, at the exact moment processing completes. It starts out
     * null and stays null until then.
     */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
