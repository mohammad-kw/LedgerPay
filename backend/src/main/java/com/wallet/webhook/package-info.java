/**
 * Razorpay webhook handling: the @RestController for
 * POST /api/webhooks/razorpay, the HMAC-SHA256 signature verification
 * logic (using the X-Razorpay-Signature header and our webhook secret),
 * and the logic that turns a verified event into a WebhookEvent row plus
 * any resulting wallet/transaction updates (PROJECT_SPEC.md Section 3.4
 * and Section 6).
 *
 * Kept as its own package (separate from com.wallet.controller) because
 * webhook endpoints are conceptually different from normal user-facing API
 * endpoints: they're called by Razorpay's servers, not a logged-in user's
 * browser, so they have no JWT auth on them at all - instead they're
 * secured entirely by verifying the request's signature. Grouping this
 * logic together keeps that distinction obvious.
 *
 * Empty for now - implemented in Phase 3 ("The Hard Part: Idempotency,
 * Webhooks, State Machine"), per PROJECT_SPEC.md Section 10.
 */
package com.wallet.webhook;
