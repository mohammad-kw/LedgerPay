package com.wallet.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A single, central place that catches exceptions thrown anywhere in our
 * controller/service layers and converts them into consistent JSON error
 * responses (see ErrorResponse's javadoc for why a consistent shape
 * matters).
 *
 * @RestControllerAdvice is a combination of two things:
 *   1. @ControllerAdvice - tells Spring "this class provides shared
 *      behaviour across MULTIPLE @RestController classes" (as opposed to
 *      @ExceptionHandler methods written directly inside one specific
 *      controller, which would only apply to that one class).
 *   2. @ResponseBody - every method's return value should be serialized
 *      directly as the HTTP response body (JSON), the same as if every
 *      method here were annotated @RestController's methods are.
 *
 * How this actually gets invoked: when ANY @RestController method (e.g.
 * AuthController.register) lets an exception propagate out of it
 * (instead of catching it itself), Spring's dispatcher intercepts that
 * exception and searches every @RestControllerAdvice class for an
 * @ExceptionHandler method whose declared exception type matches - the
 * most SPECIFIC matching type wins. So this class turns "an exception
 * was thrown" into "the client received a well-formed 4xx/5xx JSON
 * response" automatically, without any single controller method needing
 * its own try/catch block.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** users.email already exists (see DuplicateEmailException's javadoc) -> 409 Conflict. */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /** Refresh token missing/expired/revoked (see InvalidRefreshTokenException's javadoc) -> 401 Unauthorized. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, null);
    }

    /** Current user's wallet unexpectedly missing (see WalletNotFoundException's javadoc) -> 404 Not Found. */
    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /** Requested transaction doesn't exist or isn't the caller's (see TransactionNotFoundException's javadoc) -> 404 Not Found. */
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /** Admin requested a user id that doesn't exist (see UserNotFoundException's javadoc) -> 404 Not Found. */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /**
     * A transfer request that doesn't make sense - sending to yourself, or a
     * receiver we don't recognise (see InvalidTransferException's javadoc)
     * -> 400 Bad Request. The request was understood but its content is not a
     * valid transfer.
     */
    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /**
     * Sender can't afford the transfer (see InsufficientBalanceException's
     * javadoc) -> 422 Unprocessable Entity. The request was well-formed and
     * fully understood; we simply can't carry it out given the current
     * balance. 422 (rather than 400) lets the client tell "malformed request"
     * apart from "valid request you can't afford".
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /**
     * An external payment-gateway (Razorpay) call failed (see
     * PaymentGatewayException's javadoc) -> 502 Bad Gateway. The originating
     * service (e.g. TopUpService) already logs the underlying cause with the
     * business context (amount, user), so here we log a concise one-line
     * message rather than re-dumping the full stack trace - the client still
     * only ever sees a generic, safe message (the Razorpay error can carry
     * internal detail, and there's nothing the end user can do but retry).
     */
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGateway(PaymentGatewayException ex, HttpServletRequest request) {
        log.error("Payment gateway error while processing {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_GATEWAY, "Payment provider is currently unavailable. Please try again.", request, null);
    }

    /**
     * Thrown by Spring Security's AuthenticationManager itself (NOT a
     * class we wrote) when a login attempt's email+password combination
     * doesn't check out. We catch it here and deliberately respond with a
     * generic "Invalid email or password" message rather than
     * ex.getMessage() (Spring's own default message wording can vary and
     * isn't guaranteed to be safe/appropriate to show end users directly)
     * -> 401 Unauthorized.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Invalid email or password", request, null);
    }

    /**
     * Thrown automatically by Spring MVC when a @Valid-annotated
     * @RequestBody (e.g. RegisterRequest, LoginRequest) fails one or more
     * Jakarta Bean Validation constraints (@NotBlank, @Email, @Size, ...).
     * We unpack EVERY individual field failure into the fieldErrors list
     * (see ErrorResponse's javadoc) so the frontend can show the user
     * exactly which fields need fixing, all in one response -> 400 Bad
     * Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    /**
     * The final safety net: anything not specifically handled above (a
     * genuine bug, an unexpected null pointer, a database connectivity
     * issue, etc.) is caught HERE rather than letting Spring Boot's
     * default error page/handler expose an internal stack trace to the
     * client - that would both look unprofessional and could leak
     * sensitive internal details (class names, file paths, SQL) to
     * whoever's calling the API.
     *
     * We log the FULL exception (with stack trace) server-side for our
     * own debugging, but only ever send the client a generic, safe
     * message -> 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, null);
    }

    /** Small shared helper so every handler above builds an ErrorResponse the exact same way, instead of repeating this construction logic in every single method. */
    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<ErrorResponse.FieldError> fieldErrors
    ) {
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
