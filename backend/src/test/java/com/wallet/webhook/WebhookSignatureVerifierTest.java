package com.wallet.webhook;

import com.wallet.config.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link WebhookSignatureVerifier} (PROJECT_SPEC.md
 * Section 3.4/6 + Phase 4 "unit tests for... webhook signature verification").
 *
 * We construct the verifier directly with a known webhook secret (no Spring),
 * compute a REAL HMAC-SHA256 the same way Razorpay would, and assert the
 * verifier accepts the genuine signature and rejects everything else.
 */
class WebhookSignatureVerifierTest {

    private static final String WEBHOOK_SECRET = "test_webhook_secret_for_hmac";
    private static final String PAYLOAD = "{\"event\":\"payment.captured\",\"payload\":{\"x\":1}}";

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        // keyId/keySecret are irrelevant to signing; only webhookSecret matters here.
        RazorpayProperties props = new RazorpayProperties("rzp_test_x", "secret", WEBHOOK_SECRET);
        verifier = new WebhookSignatureVerifier(props);
    }

    /** Independently recompute the HMAC the way Razorpay does, so tests don't depend on the class under test. */
    private static String hmacSha256Hex(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("a correctly-signed payload is accepted")
    void validSignature_isAccepted() {
        String signature = hmacSha256Hex(PAYLOAD, WEBHOOK_SECRET);
        assertThat(verifier.isValid(PAYLOAD, signature)).isTrue();
    }

    @Test
    @DisplayName("a signature computed with the WRONG secret is rejected")
    void wrongSecretSignature_isRejected() {
        String signature = hmacSha256Hex(PAYLOAD, "some_other_secret");
        assertThat(verifier.isValid(PAYLOAD, signature)).isFalse();
    }

    /*
     * A test case you might not think of yourself: TAMPERED BODY.
     *
     * This is the whole point of signature verification. An attacker takes a
     * genuine signature for one payload but sends a DIFFERENT body (e.g. bumps
     * the amount). Because the HMAC is over the exact bytes of the body, the
     * signature no longer matches the tampered body and must be rejected. If
     * this test ever passed, an attacker could forge "captured" events at will.
     */
    @Test
    @DisplayName("a valid signature paired with a tampered body is rejected")
    void tamperedBody_isRejected() {
        String signatureForOriginal = hmacSha256Hex(PAYLOAD, WEBHOOK_SECRET);
        String tamperedBody = PAYLOAD.replace("\"x\":1", "\"x\":9999");
        assertThat(verifier.isValid(tamperedBody, signatureForOriginal)).isFalse();
    }

    @Test
    @DisplayName("a missing (null) signature header is rejected")
    void nullSignature_isRejected() {
        assertThat(verifier.isValid(PAYLOAD, null)).isFalse();
    }

    @Test
    @DisplayName("a blank signature header is rejected")
    void blankSignature_isRejected() {
        assertThat(verifier.isValid(PAYLOAD, "   ")).isFalse();
    }

    /*
     * A subtle one: the verifier compares hex case-sensitively (Razorpay emits
     * lowercase hex, which is what our computeHmacSha256Hex produces). An
     * UPPER-cased version of the otherwise-correct signature must be rejected,
     * confirming we're not doing a case-insensitive compare that could weaken
     * the check.
     */
    @Test
    @DisplayName("an upper-cased version of the correct signature is rejected (case-sensitive compare)")
    void upperCasedSignature_isRejected() {
        String signature = hmacSha256Hex(PAYLOAD, WEBHOOK_SECRET).toUpperCase();
        assertThat(verifier.isValid(PAYLOAD, signature)).isFalse();
    }
}
