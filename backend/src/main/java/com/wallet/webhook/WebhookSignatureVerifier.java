package com.wallet.webhook;

import com.wallet.config.RazorpayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies that an incoming Razorpay webhook genuinely came from Razorpay and
 * was not forged or tampered with in transit (PROJECT_SPEC.md Section 3.4 &
 * 6). This is the single most security-critical piece of the webhook flow:
 * without it, anyone who discovered our webhook URL could POST a fake
 * "payment.captured" event and trick us into crediting a wallet for money
 * that was never actually paid.
 *
 * ── How Razorpay webhook signing works ──────────────────────────────────
 * When Razorpay sends us a webhook, it:
 *   1. takes the EXACT raw JSON body of the request,
 *   2. computes HMAC-SHA256 over those raw bytes, keyed with the webhook
 *      secret that ONLY Razorpay and we know,
 *   3. hex-encodes the result, and
 *   4. puts it in the "X-Razorpay-Signature" HTTP header.
 *
 * To verify, we independently repeat steps 1-3 using the same raw body and
 * the same shared secret, then check that our computed signature equals the
 * one in the header. Because HMAC needs the secret as its key, ONLY someone
 * who knows the secret can produce a signature that matches - an attacker who
 * doesn't know it cannot forge one, even if they can see plenty of past
 * valid (payload, signature) pairs. That's the guarantee HMAC gives us:
 * authenticity (it really came from Razorpay) AND integrity (not a single
 * byte was changed).
 *
 * ── Two subtle but ESSENTIAL correctness points ─────────────────────────
 *   • RAW BYTES: we must HMAC the byte-for-byte body exactly as Razorpay
 *     sent it. If we deserialize the JSON into an object and re-serialize it,
 *     key ordering / whitespace / number formatting can change, the bytes
 *     differ, and the HMAC will not match even for a legitimate request. This
 *     is why the controller hands us the body as a raw String (read straight
 *     from the request), NOT as a parsed DTO.
 *
 *   • CONSTANT-TIME COMPARE: we compare the two signatures with
 *     MessageDigest.isEqual, which always takes the same amount of time
 *     regardless of WHERE the first difference is. A naive String.equals /
 *     '==' can bail out at the first mismatching character, and the tiny
 *     timing differences that leak can, in theory, let an attacker recover a
 *     valid signature byte-by-byte (a "timing attack"). Constant-time
 *     comparison of secrets/MACs is a standard defensive practice.
 *
 * We implement the HMAC by hand with javax.crypto here (rather than calling
 * the Razorpay SDK's built-in Utils.verifyWebhookSignature) purely so the
 * mechanism is fully visible and explainable - the spec explicitly calls this
 * out as something I must understand in depth, not treat as a black box.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureVerifier {

    /** The standard JCA (Java Cryptography Architecture) algorithm name for HMAC using SHA-256. */
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final RazorpayProperties razorpayProperties;

    /**
     * @param rawPayload the EXACT raw request body bytes-as-String Razorpay
     *                   POSTed (must not be re-serialized - see class javadoc)
     * @param signatureHeader the value of the incoming "X-Razorpay-Signature"
     *                   header (may be null if the header was absent)
     * @return true only if a signature header was present AND our recomputed
     *         HMAC matches it exactly; false in every other case (missing
     *         header, wrong signature, or an internal HMAC error)
     */
    public boolean isValid(String rawPayload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            // No signature at all -> cannot be trusted. Treat as invalid.
            log.warn("Webhook rejected: missing X-Razorpay-Signature header");
            return false;
        }

        String expected = computeHmacSha256Hex(rawPayload, razorpayProperties.webhookSecret());

        // Constant-time comparison (see class javadoc). We compare raw bytes;
        // both sides are lowercase hex ASCII, so byte comparison is correct.
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            // Do NOT log the secret or the full expected signature at info/
            // warn level in real systems; here we keep it minimal on purpose.
            log.warn("Webhook rejected: X-Razorpay-Signature did not match computed HMAC");
        }
        return matches;
    }

    /**
     * Compute HMAC-SHA256(message, key) and return it as a lowercase hex
     * string - exactly the format Razorpay uses for the signature header.
     *
     * Step by step:
     *   1. SecretKeySpec wraps our webhook-secret bytes as an HMAC key.
     *   2. Mac.getInstance("HmacSHA256") gives us a JCA MAC engine for the
     *      HMAC-SHA256 algorithm.
     *   3. mac.init(key) keys the engine with our secret.
     *   4. mac.doFinal(messageBytes) runs the algorithm over the payload and
     *      returns the 32 raw bytes (256 bits) of the MAC.
     *   5. HexFormat.of().formatHex(...) turns those 32 bytes into a 64-char
     *      lowercase hex string.
     *
     * Both the key and the message are encoded as UTF-8 bytes, which is what
     * Razorpay uses; using a different charset would produce a different (and
     * therefore non-matching) HMAC.
     */
    private String computeHmacSha256Hex(String message, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception ex) {
            // getInstance / init can theoretically throw
            // NoSuchAlgorithmException / InvalidKeyException. HmacSHA256 is
            // guaranteed present on every standard JVM and our key is always
            // valid, so this is effectively unreachable - but if it ever
            // happens we must FAIL CLOSED (treat as "cannot verify" ->
            // invalid), never fail open. Returning a value that can't match
            // any real signature guarantees isValid(...) yields false.
            log.error("Unable to compute webhook HMAC (failing closed -> reject): {}", ex.getMessage());
            return "";
        }
    }
}
