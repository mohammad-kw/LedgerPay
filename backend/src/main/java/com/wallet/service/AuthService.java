package com.wallet.service;

import com.wallet.dto.AuthResponse;
import com.wallet.dto.LoginRequest;
import com.wallet.dto.RegisterRequest;
import com.wallet.dto.RegisterResponse;
import com.wallet.entity.RefreshToken;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.exception.DuplicateEmailException;
import com.wallet.exception.InvalidRefreshTokenException;
import com.wallet.repository.RefreshTokenRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.security.JwtService;
import com.wallet.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The business logic for everything in PROJECT_SPEC.md Section 5's "Auth"
 * table: register, login, refresh. AuthController (a thin HTTP-facing
 * layer) delegates all real work to this class - see
 * com.wallet.service's package-info.java for why we keep that separation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    /**
     * POST /api/auth/register - PROJECT_SPEC.md Section 5:
     * "Create a new user + auto-create their wallet", and Section 10
     * Phase 1: "Auto-create a wallet when a user registers".
     *
     * @Transactional is critical here and is worth being able to explain
     * clearly in an interview: this method performs TWO separate inserts
     * (one User row, one Wallet row). Without @Transactional, if the
     * application crashed or the database connection dropped between
     * those two inserts, we could end up with a User that has NO wallet -
     * an inconsistent state the rest of the application is never designed
     * to handle. @Transactional wraps both inserts in a single database
     * transaction: either BOTH succeed and are committed together, or if
     * anything throws an exception, BOTH are rolled back as if neither
     * ever happened. This "all or nothing" guarantee is called atomicity
     * (the "A" in the classic ACID properties of a database transaction),
     * and it's the exact same principle PROJECT_SPEC.md Section 3.2 relies
     * on for ledger entries later - this is our first, simplest example of
     * it in this codebase.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // Fail fast with a clear, specific error rather than letting this
        // hit the database's UNIQUE constraint on users.email and bubble
        // up as an opaque DataIntegrityViolationException.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException("An account with email '" + request.email() + "' already exists");
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                // NEVER store request.password() directly - passwordEncoder.encode(...)
                // runs BCrypt on it, and only the resulting one-way hash is
                // persisted (PROJECT_SPEC.md Section 7). The raw password
                // is never written anywhere - not to the database, not to
                // logs, nothing.
                .passwordHash(passwordEncoder.encode(request.password()))
                .phone(request.phone())
                .build();
        user = userRepository.save(user);

        // Auto-create the wallet. Wallet.balance defaults to
        // BigDecimal.ZERO and currency defaults to "INR" via
        // @Builder.Default (see Wallet.java), so we don't need to set
        // them explicitly here - but we spell `balance` out anyway below
        // purely for readability, so it's immediately obvious at a glance
        // that every new wallet starts at zero, matching this task's
        // explicit requirement ("auto-create wallet with balance 0").
        Wallet wallet = Wallet.builder()
                .user(user)
                .balance(BigDecimal.ZERO)
                .build();
        walletRepository.save(wallet);

        log.info("Registered new user id={} with auto-created wallet id={}", user.getId(), wallet.getId());

        return new RegisterResponse(user.getId(), user.getName(), user.getEmail());
    }

    /**
     * POST /api/auth/login - PROJECT_SPEC.md Section 5:
     * "Returns JWT access token + refresh token".
     *
     * Notice this method does NOT manually fetch the user and compare
     * password hashes itself. Instead it delegates that entire check to
     * Spring Security's AuthenticationManager, which internally calls our
     * CustomUserDetailsService (to load the user) and PasswordEncoder (to
     * compare the submitted password against the stored BCrypt hash) - see
     * SecurityConfig for how those pieces are wired together. If the
     * credentials don't match, `authenticate(...)` itself throws
     * BadCredentialsException, which GlobalExceptionHandler converts into
     * a 401 response - we don't need our own if/else check for that here
     * at all.
     */
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        // If authenticate(...) returned normally (didn't throw), the
        // principal attached to the result is guaranteed to be the
        // UserPrincipal our CustomUserDetailsService constructed - see
        // UserPrincipal.java.
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = principal.getUser();

        return issueTokens(user);
    }

    /**
     * POST /api/auth/refresh - PROJECT_SPEC.md Section 5:
     * "Get a new access token using refresh token".
     *
     * This method implements REFRESH TOKEN ROTATION: every time a refresh
     * token is used, we immediately revoke it and issue a brand new one
     * alongside the new access token, rather than letting the same
     * refresh token be reused indefinitely until its expiry. This is a
     * well-known security best practice worth being able to explain in an
     * interview: if a refresh token is ever stolen (e.g. leaked from
     * insecure client storage), rotation means it can only be used ONCE
     * before it stops working - the very next legitimate refresh call
     * from the real user invalidates it. Some production systems go
     * further and treat "an already-revoked refresh token was just reused"
     * as a signal of theft and revoke ALL of that user's sessions
     * (RefreshTokenRepository.revokeAllByUserId exists for exactly that
     * future enhancement) - we keep this phase's version simple and just
     * reject the reused token.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not recognized"));

        if (storedToken.getRevoked()) {
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }
        if (storedToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        // Rotation: kill this one now, before issuing the replacement,
        // so that even if something below throws, this specific token
        // can never be used again.
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        return issueTokens(user);
    }

    /**
     * Shared by both login() and refresh(): mint a new access token (a
     * signed JWT, via JwtService) plus a new refresh token (a random
     * opaque string, persisted to the refresh_tokens table) for a given
     * user, and package them into the response shape the API returns.
     * Pulling this into one shared private method means login and
     * refresh can never accidentally issue tokens in two subtly different
     * ways.
     */
    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());

        // UUID.randomUUID() gives us a cryptographically strong random
        // 128-bit value formatted as a string like
        // "3fa85f64-5717-4562-b3fc-2c963f66afa6" - see RefreshToken.java's
        // javadoc for why this is a plain random string and NOT a JWT.
        String refreshTokenValue = UUID.randomUUID().toString();

        RefreshToken refreshToken = RefreshToken.builder()
                .token(refreshTokenValue)
                .user(user)
                .expiryDate(LocalDateTime.now().plus(Duration.ofMillis(jwtService.getRefreshTokenExpiryMs())))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenValue, jwtService.getAccessTokenExpirySeconds());
    }
}
