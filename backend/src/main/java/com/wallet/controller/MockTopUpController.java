package com.wallet.controller;

import com.wallet.dto.MockTopUpResponse;
import com.wallet.dto.TopUpInitiateRequest;
import com.wallet.security.UserPrincipal;
import com.wallet.service.MockTopUpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV / DEMO ONLY - HTTP entry point for the mock (Razorpay-bypassing) top-up.
 * See {@link MockTopUpService} for the full rationale and the guarantees this
 * flow keeps (it reuses the real double-entry ledger + state-machine logic).
 *
 * SECURITY / SAFETY
 * -----------------
 * This whole controller is annotated {@code @ConditionalOnProperty(name =
 * "app.mock-topup.enabled", havingValue = "true")}, so Spring only registers
 * it - and therefore the POST /api/wallet/topup/mock route only EXISTS - when
 * that flag is explicitly set to true (via the MOCK_TOPUP_ENABLED env var).
 * With the shipped default of false, the bean is never created and the route
 * returns 404. This guarantees an endpoint that mints money on demand can
 * never be accidentally exposed in a real deployment.
 *
 * Note this route still lives under /api/wallet/** and so is NOT in
 * SecurityConfig's permitAll() list - it requires a valid JWT like every other
 * wallet endpoint. A caller can therefore only ever fund their OWN wallet (the
 * user id comes from the token, never the body), same as the real top-up.
 */
@RestController
@RequestMapping("/api/wallet")
@ConditionalOnProperty(name = "app.mock-topup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockTopUpController {

    private final MockTopUpService mockTopUpService;

    /**
     * POST /api/wallet/topup/mock
     *
     * Instantly credit the authenticated user's wallet, simulating a captured
     * Razorpay payment. Inputs follow the same three-source design as the real
     * top-up (see WalletController.initiateTopUp):
     *   - @AuthenticationPrincipal - WHO is topping up (from the verified JWT).
     *   - @RequestHeader("Idempotency-Key") - the unique key for this logical
     *     request (Section 3.1); a repeat returns the original result.
     *   - @Valid @RequestBody TopUpInitiateRequest - the amount (reused from the
     *     real top-up so the same validation bounds apply).
     *
     * Returns 200 OK with the completed top-up (the wallet is already credited),
     * unlike the real initiate which returns 201 with an as-yet-unpaid order.
     */
    @PostMapping("/topup/mock")
    public ResponseEntity<MockTopUpResponse> mockTopUp(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TopUpInitiateRequest request) {

        Long userId = principal.getUser().getId();
        MockTopUpResponse response =
                mockTopUpService.mockTopUp(userId, request.amount(), idempotencyKey);
        return ResponseEntity.ok(response);
    }
}
