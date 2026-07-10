package com.wallet.repository;

import com.wallet.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link WebhookEvent}. See UserRepository's
 * javadoc for a general explanation of how these interfaces work (we declare
 * the method, Spring Data generates the implementation from its name).
 *
 * This backs the duplicate-webhook defense required by PROJECT_SPEC.md
 * Section 3.4 and the Phase 3 checklist ("Handle duplicate webhook delivery -
 * Razorpay may send the same event more than once").
 */
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /**
     * Have we already stored an event with this Razorpay event id?
     *
     * Razorpay (like all robust webhook providers) guarantees AT-LEAST-ONCE
     * delivery: to be sure we received an event, it may send the very same
     * one two or more times (e.g. if our first response was slow or a network
     * blip made Razorpay unsure we got it). Every event carries a stable,
     * unique id (the "x-razorpay-event-id" header / payload id). Before doing
     * any processing we call this method; if it returns true, we've handled
     * this event already and safely skip it - the webhook counterpart of the
     * Idempotency-Key check used for top-up/transfer requests.
     *
     * Spring Data derives the query from the method name: "existsBy" +
     * "RazorpayEventId" becomes "SELECT COUNT(...) > 0 FROM WebhookEvent w
     * WHERE w.razorpayEventId = :id". existsBy is more efficient than loading
     * the whole row just to check presence.
     *
     * Note the UNIQUE constraint on webhook_events.razorpay_event_id is the
     * ultimate backstop: even if two duplicate deliveries raced past this
     * check simultaneously, the second INSERT would fail at the database
     * level, so a duplicate can never actually be stored twice.
     */
    boolean existsByRazorpayEventId(String razorpayEventId);
}
