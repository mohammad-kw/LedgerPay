package com.wallet.webhook;

import com.wallet.webhook.WebhookService.WebhookResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP entry point for Razorpay webhooks: POST /api/webhooks/razorpay
 * (PROJECT_SPEC.md Section 5). Like our other controllers it stays THIN - it
 * does no business logic itself, it just receives the raw request and hands it
 * to WebhookService, then maps the result to an HTTP status.
 *
 * Two things make this controller different from the user-facing ones:
 *
 *   1. NO JWT. This endpoint is called by Razorpay's servers, which have no
 *      user login. It's made public in SecurityConfig and is instead secured
 *      by HMAC signature verification inside WebhookService.
 *
 *   2. RAW STRING BODY, not a DTO. We take @RequestBody as a String, i.e. the
 *      exact bytes Razorpay sent. This is essential: the signature is computed
 *      over that raw body, so if we let Spring deserialize it into an object
 *      and later re-serialize, the bytes (and therefore the HMAC) could differ
 *      and verification would wrongly fail. Keeping it a String preserves the
 *      payload verbatim for WebhookSignatureVerifier. (See that class's
 *      javadoc for the full explanation.)
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * POST /api/webhooks/razorpay
     *
     * @param payload   the raw JSON body, taken verbatim as a String (see
     *                  class javadoc for why it must NOT be a parsed DTO)
     * @param signature the X-Razorpay-Signature header. required = false so
     *                  that a call with NO signature reaches our code and is
     *                  rejected with a clean 400 by our own logic, rather than
     *                  Spring returning a generic error before we can respond.
     *
     * Status mapping:
     *   200 OK          -> processed OR duplicate. In BOTH cases we want
     *                      Razorpay to consider delivery successful and stop
     *                      retrying (a duplicate means we already have it).
     *   400 Bad Request -> signature verification failed. Signals the caller
     *                      that the request was not accepted. A genuine forged
     *                      request lands here and is safely discarded.
     *
     * Note we return 200 even for an unrecognised order or an unhandled event
     * type: those are stored/audited and are not Razorpay's fault, so making
     * Razorpay retry them would be pointless noise.
     */
    @PostMapping("/razorpay")
    public ResponseEntity<Void> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        WebhookResult result = webhookService.process(payload, signature);

        return switch (result) {
            case PROCESSED, DUPLICATE -> ResponseEntity.ok().build();
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        };
    }
}
