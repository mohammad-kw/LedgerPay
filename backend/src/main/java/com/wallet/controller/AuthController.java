package com.wallet.controller;

import com.wallet.dto.AuthResponse;
import com.wallet.dto.LoginRequest;
import com.wallet.dto.RefreshRequest;
import com.wallet.dto.RegisterRequest;
import com.wallet.dto.RegisterResponse;
import com.wallet.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thin, HTTP-facing layer for PROJECT_SPEC.md Section 5's "Auth"
 * endpoint table. Every method here does the same three things, in
 * order, and NOTHING more:
 *   1. Receive/validate the incoming JSON body (validation happens
 *      automatically via @Valid before our method body even runs - see
 *      RegisterRequest's javadoc).
 *   2. Delegate to AuthService for all actual business logic.
 *   3. Wrap the result in a ResponseEntity with the right HTTP status
 *      code.
 * See com.wallet.controller's package-info.java for why controllers stay
 * this "thin" in general throughout this codebase.
 *
 * @RestController = @Controller + @ResponseBody: every method's return
 * value is automatically serialized to JSON and written to the HTTP
 * response body (via Jackson, brought in by spring-boot-starter-web) -
 * we never manually convert anything to a JSON string ourselves.
 *
 * @RequestMapping("/api/auth") sets a shared URL prefix for every
 * endpoint in this class, so each method below only needs to specify the
 * REST of its path - matching PROJECT_SPEC.md Section 5's endpoint table
 * exactly (/api/auth/register, /api/auth/login, /api/auth/refresh).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/auth/register
     *
     * @Valid tells Spring "before calling this method, run every Bean
     * Validation constraint declared on RegisterRequest's fields (see
     * RegisterRequest.java) against the parsed JSON body". If any
     * constraint fails, Spring throws MethodArgumentNotValidException
     * BEFORE this method body ever executes, and
     * GlobalExceptionHandler.handleValidation(...) turns that into a 400
     * response automatically - this method never needs to check
     * "is the name blank?" itself.
     *
     * Returns 201 Created (not the default 200 OK) because this endpoint
     * successfully creates a brand-new resource (a User row, plus its
     * Wallet) - 201 is the semantically correct HTTP status for "a new
     * resource now exists as a result of this request".
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/auth/login
     *
     * Returns 200 OK (ResponseEntity.ok(...) is just a shorthand for
     * ResponseEntity.status(HttpStatus.OK).body(...)) since logging in
     * doesn't create a new resource - it just returns a token pair for an
     * already-existing user.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/refresh
     *
     * Notice this passes `request.refreshToken()` (a plain String, using
     * the record's auto-generated accessor - see RegisterRequest's
     * javadoc for why records use `.fieldName()` instead of a
     * `.getFieldName()`) into AuthService, rather than passing the whole
     * RefreshRequest object. This keeps AuthService's method signature
     * (refresh(String)) decoupled from the specific shape of the HTTP
     * request DTO - AuthService has no idea (and shouldn't need to know)
     * that this value originally came from a JSON field called
     * "refreshToken".
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(response);
    }
}
