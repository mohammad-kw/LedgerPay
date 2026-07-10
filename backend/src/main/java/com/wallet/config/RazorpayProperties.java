package com.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for the "razorpay.*" properties in
 * application.properties (razorpay.key-id, razorpay.key-secret). This is
 * the exact same pattern as com.wallet.security.JwtProperties - see that
 * class's javadoc for WHY we prefer a @ConfigurationProperties record over
 * scattering @Value("${razorpay.key-id}") annotations around the codebase
 * (type safety, single source of truth, IDE autocomplete).
 *
 * Spring binds "razorpay.key-id" (kebab-case in the properties file) to the
 * {@code keyId} record component (camelCase) automatically - this is Spring
 * Boot's "relaxed binding".
 *
 * Registered as a bean via @EnableConfigurationProperties on
 * RazorpayConfig (see that class), NOT via a @Component annotation here.
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayProperties(

        /**
         * The Razorpay TEST-mode key id (looks like "rzp_test_...."). Unlike
         * the secret, this value is NOT confidential - it is deliberately
         * sent to the browser so Razorpay Checkout.js can identify which
         * merchant account a payment belongs to. See Razorpay Checkout
         * integration in the frontend.
         */
        String keyId,

        /**
         * The Razorpay key SECRET. This one IS confidential and must NEVER
         * leave the server or appear in any response to the browser: it is
         * used only here on the backend, to authenticate our server-to-server
         * calls to Razorpay's API when creating an Order (and later, in
         * Phase 3, to verify webhook signatures). Keep it in the
         * RAZORPAY_KEY_SECRET environment variable in real deployments
         * (PROJECT_SPEC.md Section 7).
         */
        String keySecret,

        /**
         * The Razorpay WEBHOOK signing secret. This is a THIRD, SEPARATE
         * secret from keyId/keySecret above - you set it yourself when you
         * create a webhook in the Razorpay dashboard (Settings -> Webhooks),
         * and Razorpay uses it to HMAC-sign every webhook payload it sends
         * us. We use it here on the backend ONLY to recompute that same HMAC
         * and confirm an incoming webhook genuinely came from Razorpay and
         * wasn't forged or tampered with in transit (PROJECT_SPEC.md Section
         * 3.4 & 6). Like keySecret it is confidential and must never leave
         * the server; keep it in the RAZORPAY_WEBHOOK_SECRET environment
         * variable in real deployments (Section 7).
         *
         * IMPORTANT: the webhook secret and the API key secret are NOT
         * interchangeable - signing a webhook uses this webhook secret, while
         * authenticating an Orders API call uses keySecret. Mixing them up is
         * a common integration mistake that makes every signature check fail.
         */
        String webhookSecret
) {
}
