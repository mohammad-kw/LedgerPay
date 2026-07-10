package com.wallet.dto;

/**
 * The JSON response body for POST /api/wallet/topup/initiate
 * (PROJECT_SPEC.md Section 5). See RegisterResponse's javadoc for why this
 * is a Java `record`.
 *
 * This carries exactly what the frontend's Razorpay Checkout.js needs to
 * open the payment popup, and nothing more. Crucially it contains the
 * key ID (safe to expose to the browser) but NEVER the key secret (see
 * RazorpayProperties.keySecret) - the secret stays server-side.
 */
public record TopUpInitiateResponse(

        /** Our OWN transaction row's id (status CREATED). Useful for the frontend to correlate, and for later webhook/reconciliation lookups. */
        Long transactionId,

        /** The Razorpay Order id (looks like "order_ABC123"). Checkout.js needs this to associate the payment with the order we created server-side. */
        String razorpayOrderId,

        /** Our Razorpay TEST-mode key id (rzp_test_...). Passed to Checkout.js so it knows which merchant account this payment is for - deliberately NOT secret. */
        String razorpayKeyId,

        /**
         * The order amount in the SMALLEST currency unit - paise for INR
         * (i.e. rupees * 100). Razorpay's entire API works in the minor
         * unit, so ₹500.00 is represented as 50000. We echo the exact value
         * we sent to Razorpay so the frontend and gateway can never disagree
         * on the amount.
         */
        long amountInPaise,

        /** ISO currency code, e.g. "INR". */
        String currency
) {
}
