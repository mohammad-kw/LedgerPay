package com.wallet.security;

import com.wallet.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A custom Spring Security filter that runs on (almost) every incoming
 * HTTP request, BEFORE it reaches our @RestController methods. Its job:
 *   1. Look for an "Authorization: Bearer {token}" header.
 *   2. If present, verify the token's signature/expiry (via JwtService).
 *   3. If valid, tell Spring Security "this request is authenticated as
 *      this specific user" by populating the SecurityContext.
 *   4. Either way, let the request continue down the filter chain -
 *      this filter never itself decides "reject this request". That
 *      decision is made later, by SecurityConfig's authorizeHttpRequests
 *      rules, based on whether step 3 successfully authenticated someone.
 *
 * Extending OncePerRequestFilter (rather than the more generic
 * jakarta.servlet.Filter) guarantees Spring only invokes this filter's
 * logic ONCE per request, even in complex setups where a request might
 * otherwise be internally forwarded/included multiple times within the
 * same servlet container call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);

        // No Authorization header, or it doesn't start with "Bearer " ->
        // nothing for us to do. We do NOT reject the request here - it's
        // entirely possible this request is hitting a public endpoint
        // (like /api/auth/login itself!) that never required a token in
        // the first place. We just move on down the chain unauthenticated,
        // and let SecurityConfig's rules decide afterwards whether that's
        // actually OK for this specific URL.
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Strip off the literal "Bearer " prefix to get just the raw
        // compact JWT string, e.g. "Bearer eyJhbGc..." -> "eyJhbGc...".
        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            String email = jwtService.extractEmail(token);

            // A subtle but important check: only try to authenticate if
            // there ISN'T already an authenticated user set for this
            // request. In practice this filter runs once per request
            // anyway (see the OncePerRequestFilter note above), but
            // checking SecurityContextHolder here is standard,
            // defensive practice recommended by Spring Security's own
            // documentation/examples for this exact kind of filter.
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    UserPrincipal principal = new UserPrincipal(user);

                    // This is the actual object Spring Security's
                    // SecurityContext stores. Passing `principal.getAuthorities()`
                    // as the third argument (instead of null) is what marks
                    // this token as ALREADY AUTHENTICATED - Spring Security
                    // trusts us here because WE already did the real work
                    // of verifying the JWT's signature above, so there's no
                    // password to re-check at this point (unlike a normal
                    // username+password login).
                    var authToken = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());

                    // Attaches extra request metadata (like the caller's IP
                    // address and session id) to the authentication object -
                    // standard boilerplate copied from Spring Security's own
                    // recommended filter examples, useful for audit logging.
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // THE key line: from this point on, for the rest of
                    // this single request, Spring Security considers the
                    // caller authenticated as this user. Any
                    // @RestController can now ask
                    // "who is the current user?" via the SecurityContext.
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                });
            }
        } catch (JwtException ex) {
            // The token was missing, expired, tampered with, or otherwise
            // invalid (JwtService.parseAndValidate/extractEmail throws
            // subclasses of JwtException for all of these cases - see
            // JwtService's javadoc). We deliberately do NOT throw this
            // further or write an error response ourselves here - we just
            // log it and let the request continue UNAUTHENTICATED.
            // SecurityConfig's rules will then correctly reject it with a
            // 401/403 for any endpoint that actually requires
            // authentication, which is a cleaner separation of concerns
            // than having this filter also be responsible for writing
            // error responses.
            log.debug("Rejected invalid JWT on request to {}: {}", request.getRequestURI(), ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
