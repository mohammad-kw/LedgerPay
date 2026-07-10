package com.wallet.controller;

import com.wallet.dto.BalanceResponse;
import com.wallet.dto.PageResponse;
import com.wallet.dto.TopUpInitiateRequest;
import com.wallet.dto.TopUpInitiateResponse;
import com.wallet.dto.TransactionResponse;
import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.entity.TransactionStatus;
import com.wallet.security.UserPrincipal;
import com.wallet.service.TopUpService;
import com.wallet.service.TransferService;
import com.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thin, HTTP-facing layer for the read-only "Wallet" endpoints in
 * PROJECT_SPEC.md Section 5:
 *   - GET /api/wallet/balance
 *   - GET /api/wallet/transactions?page=&status=
 *
 * As with AuthController, every method here stays thin: it pulls the
 * authenticated user out of the security context, delegates all real work
 * to WalletService, and wraps the result in a ResponseEntity. See
 * com.wallet.controller's package-info.java for why controllers stay thin.
 *
 * These endpoints are NOT listed in SecurityConfig's permitAll() rules, so
 * they fall under its ".anyRequest().authenticated()" catch-all - meaning
 * Spring Security rejects any request without a valid JWT with a 401 BEFORE
 * it ever reaches these methods. By the time a method body runs, we're
 * guaranteed to have an authenticated user.
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TopUpService topUpService;
    private final TransferService transferService;

    /**
     * GET /api/wallet/balance
     *
     * @AuthenticationPrincipal injects the UserPrincipal that
     * JwtAuthenticationFilter placed into the SecurityContext after
     * verifying this request's JWT (see that filter's javadoc). We read the
     * user's id straight from it - crucially, the client never sends a user
     * or wallet id, so a caller can only ever retrieve their OWN balance
     * (see WalletService's security-model note).
     */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal.getUser().getId();
        return ResponseEntity.ok(walletService.getBalance(userId));
    }

    /**
     * GET /api/wallet/transactions?page=&size=&status=
     *
     * All three query params are optional:
     *   - page   defaults to 0 (the first page).
     *   - size   defaults to 0, which WalletService treats as "use the
     *            default page size" (it clamps/defaults the value).
     *   - status when present, must be one of the TransactionStatus enum
     *            names (CREATED, PENDING, SUCCESS, FAILED, REVERSED). Spring
     *            converts the string to the enum automatically; an unknown
     *            value results in a 400 handled by GlobalExceptionHandler.
     *            When omitted it stays null, meaning "all statuses".
     *
     * required = false + a primitive default is why these can be left off
     * the URL entirely.
     */
    @GetMapping("/transactions")
    public ResponseEntity<PageResponse<TransactionResponse>> getTransactions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "0") int size,
            @RequestParam(required = false) TransactionStatus status) {

        Long userId = principal.getUser().getId();
        PageResponse<TransactionResponse> response =
                walletService.getTransactions(userId, page, size, status);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/wallet/transactions/{id}
     *
     * Return a single transaction by id (PROJECT_SPEC.md Section 5). Like the
     * list endpoint, it's scoped to the authenticated user: WalletService only
     * returns the transaction if the caller's own wallet is its sender or
     * receiver, otherwise it throws TransactionNotFoundException (-> 404). The
     * id comes from the URL path via @PathVariable, but is never trusted to
     * imply ownership - that check happens in the service (IDOR defense).
     */
    @GetMapping("/transactions/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        Long userId = principal.getUser().getId();
        return ResponseEntity.ok(walletService.getTransaction(userId, id));
    }

    /**
     * POST /api/wallet/topup/initiate
     *
     * Step ONE of the two-step Razorpay top-up flow (see TopUpService's
     * javadoc for the full picture). This endpoint creates a Razorpay order
     * plus a local transaction in status CREATED, and returns what the
     * browser needs to open Razorpay Checkout. It deliberately does NOT
     * credit the wallet - that only happens in Phase 3 when the signed
     * webhook confirms the payment (PROJECT_SPEC.md Section 3.4).
     *
     * The three inputs come from three different places, on purpose:
     *   - @AuthenticationPrincipal - WHO is topping up. Taken from the
     *     verified JWT, never from the request body, so a caller can only
     *     ever top up their OWN wallet.
     *   - @RequestHeader("Idempotency-Key") - the client-generated unique
     *     key for this logical request (PROJECT_SPEC.md Section 3.1). It
     *     lives in a HEADER rather than the body because idempotency is a
     *     property of the HTTP request itself, not of the business payload;
     *     this also matches the convention used by Stripe/Razorpay. If the
     *     same key arrives twice (double-click, retry), TopUpService returns
     *     the original order instead of creating a duplicate. required=true,
     *     so a request without the header is rejected with a 400.
     *   - @Valid @RequestBody - WHAT amount to add. @Valid triggers the Bean
     *     Validation rules on TopUpInitiateRequest (positive, within bounds,
     *     <=2 decimals); a violation becomes a 400 via GlobalExceptionHandler.
     *
     * Returns 201 Created - we are creating a new order + transaction
     * resource, and 201 communicates that more precisely than a plain 200.
     */
    @PostMapping("/topup/initiate")
    public ResponseEntity<TopUpInitiateResponse> initiateTopUp(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TopUpInitiateRequest request) {

        Long userId = principal.getUser().getId();
        TopUpInitiateResponse response =
                topUpService.initiateTopUp(userId, request.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/wallet/transfer
     *
     * Move money from the authenticated user's wallet to another user's
     * wallet (PROJECT_SPEC.md Section 5). All the correctness-critical work -
     * the idempotency dedupe, the balance check, the double-entry ledger, and
     * the atomic balance updates - lives in TransferService; this method just
     * wires the HTTP inputs to it.
     *
     * Inputs, from three sources (same design as top-up):
     *   - @AuthenticationPrincipal  - WHO is sending. From the verified JWT,
     *     never the body, so a caller can only ever send from their OWN wallet.
     *   - @RequestHeader("Idempotency-Key") - the unique key for this logical
     *     request (Section 3.1). In a header, not the body. If the same key
     *     arrives twice (double-click/retry), TransferService returns the
     *     original result instead of moving money again. required=true, so a
     *     missing header is a 400.
     *   - @Valid @RequestBody - WHOM to pay (receiverEmail) and HOW MUCH
     *     (amount). @Valid enforces TransferRequest's constraints; a violation
     *     becomes a 400 via GlobalExceptionHandler.
     *
     * Returns 200 OK (not 201): a transfer isn't really "creating a resource
     * the client will address later" so much as executing an action; 200 with
     * the outcome body is the natural fit. Error statuses are produced by the
     * service's exceptions: 400 (invalid transfer), 422 (insufficient balance).
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        Long userId = principal.getUser().getId();
        TransferResponse response = transferService.transfer(
                userId, request.receiverEmail(), request.amount(), idempotencyKey);
        return ResponseEntity.ok(response);
    }
}
