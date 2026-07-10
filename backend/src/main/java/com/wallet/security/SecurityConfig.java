package com.wallet.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * The central configuration class for Spring Security - this is where we
 * describe, as a series of small declarative rules, exactly how HTTP
 * security should behave for this application: which URLs are public,
 * which require a valid JWT, how passwords get hashed, and where our
 * custom JwtAuthenticationFilter fits into Spring's request-processing
 * pipeline.
 *
 * @Configuration tells Spring "scan this class for @Bean methods and
 * register their return values as managed beans" (see LedgerPayApplication's
 * javadoc for how @ComponentScan finds this class in the first place).
 *
 * @EnableConfigurationProperties(JwtProperties.class) is what actually
 * activates JwtProperties as a real Spring bean, bound from the "jwt.*"
 * keys in application.properties (see JwtProperties's javadoc).
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * Comma-separated list of browser origins allowed to call this API
     * (CORS). In local dev the Vite proxy makes CORS irrelevant, so this
     * defaults to the dev frontend. In production, set the deployed
     * frontend URL(s) via the CORS_ALLOWED_ORIGINS environment variable,
     * e.g. "https://ledgerpay.vercel.app". Without this, the browser
     * blocks the deployed frontend's requests to a different-origin API.
     */
    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    /**
     * The single, most important bean in this class: it defines the
     * ordered chain of rules Spring Security applies to every incoming
     * HTTP request.
     *
     * Method-chaining style (`.csrf(...).sessionManagement(...)...`) is
     * Spring Security's modern (post-5.7) configuration API - each call
     * configures one aspect and returns the same builder for the next
     * call, ending in `.build()` to produce the final SecurityFilterChain.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) throws Exception {
        http
                // Enable CORS using the corsConfigurationSource() bean
                // below, so the deployed frontend (a different origin than
                // the backend) is allowed to call this API from the browser.
                .cors(cors -> {})

                // CSRF (Cross-Site Request Forgery) protection is a
                // browser-cookie-session-based defense mechanism - it
                // matters when the browser automatically attaches
                // credentials (cookies) to every request, including ones
                // a malicious site could trick a user's browser into
                // making. Our API is stateless and uses an explicit
                // "Authorization: Bearer {token}" header that a malicious
                // 3rd-party site cannot silently attach on a victim's
                // behalf (unlike cookies), so traditional CSRF protection
                // does not apply here and would only add friction for
                // zero benefit.
                .csrf(AbstractHttpConfigurer::disable)

                // Tell Spring Security we NEVER want it to create or rely
                // on an HttpSession to remember who's logged in between
                // requests (the traditional "session cookie" approach).
                // Every single request must instead prove who it is via
                // its JWT, independently, every time - this is what
                // "stateless authentication" means and is the standard
                // approach for JWT-based APIs.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // The actual allow/deny rules, evaluated top-to-bottom -
                // the FIRST matching rule wins, so order matters.
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints (Section 5) must be reachable by
                        // someone who is NOT yet logged in - that's the
                        // whole point of register/login/refresh.
                        .requestMatchers("/api/auth/**").permitAll()
                        // Actuator health check (Section 5) needs to be
                        // reachable by uptime monitors with no credentials.
                        .requestMatchers("/actuator/health").permitAll()
                        // The Razorpay webhook (Section 5) is called by
                        // Razorpay's SERVERS, not a logged-in user's browser,
                        // so it carries no JWT and must be reachable without
                        // one. It is NOT actually "unprotected", though: it is
                        // secured by a different mechanism entirely - every
                        // call's X-Razorpay-Signature header is HMAC-verified
                        // against our webhook secret inside WebhookService,
                        // and anything that fails is rejected. Authenticating
                        // it via JWT here would be impossible (Razorpay has no
                        // way to obtain or send one).
                        .requestMatchers("/api/webhooks/**").permitAll()
                        // Admin endpoints require BOTH a valid JWT AND the
                        // ADMIN role (ROLE_ADMIN authority - see
                        // UserPrincipal.getAuthorities). A logged-in ordinary
                        // user hitting these gets 403 Forbidden (authenticated
                        // but not authorized), while an anonymous request gets
                        // 401. This rule must come BEFORE anyRequest() below,
                        // since the first matching rule wins.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else defined so far in this codebase
                        // requires a successfully-authenticated request.
                        // (Wallet/webhook/admin endpoints don't exist as
                        // controllers yet - this is future-proofing so
                        // that whenever they ARE added, they're secure by
                        // default rather than accidentally public.)
                        .anyRequest().authenticated()
                )

                // Insert our custom JWT-checking filter INTO Spring
                // Security's existing filter chain, specifically placing
                // it immediately before the filter that would normally
                // handle traditional username+password form logins. Our
                // filter runs on every request and, if a valid JWT is
                // found, populates the SecurityContext (see
                // JwtAuthenticationFilter's javadoc) so that by the time
                // the .authorizeHttpRequests rules above are evaluated,
                // Spring Security already knows whether this request is
                // authenticated or not.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Defines the CORS policy Spring Security applies (activated by the
     * `.cors(...)` call above). Allowed origins come from the
     * CORS_ALLOWED_ORIGINS env var (comma-separated) so we never hard-code
     * the deployed frontend URL. We allow the standard REST methods plus
     * the custom headers this API uses (Authorization for the JWT, and
     * Idempotency-Key on mutating calls), and expose X-Request-Id so the
     * frontend can read the correlation id if needed.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Razorpay-Signature"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * The BCrypt password hasher (PROJECT_SPEC.md Section 7: "Passwords
     * hashed with BCrypt — never stored in plain text"), exposed as a
     * Spring bean so it can be injected (via constructor injection)
     * wherever it's needed - AuthService uses it to hash a new password
     * on registration and compare passwords is instead delegated to
     * AuthenticationManager during login.
     *
     * BCrypt is deliberately slow (it's designed to be, via a
     * configurable "work factor") which is exactly what you want for
     * password hashing: it makes brute-forcing a stolen password
     * database computationally expensive, unlike a fast general-purpose
     * hash like SHA-256 which is actually a POOR choice for passwords
     * specifically because it's too fast to compute at scale.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Spring Security's central "verify these credentials" coordinator.
     * AuthService calls this bean directly during login, passing in the
     * email+password the user typed - the AuthenticationManager then
     * internally calls CustomUserDetailsService to load the real user and
     * PasswordEncoder to compare the hashes, throwing
     * BadCredentialsException if they don't match.
     *
     * We don't build this ourselves from scratch - Spring Security
     * already assembles a fully-configured AuthenticationManager
     * internally (wiring together our CustomUserDetailsService and
     * PasswordEncoder beans automatically, since we haven't overridden
     * that default wiring). AuthenticationConfiguration is just the
     * standard hook Spring Security provides to retrieve that
     * already-built instance and expose it as our own bean.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Not currently used anywhere else in this codebase (Spring Security
     * assembles an equivalent DaoAuthenticationProvider automatically
     * behind the authenticationManager() bean above, using our
     * CustomUserDetailsService and passwordEncoder() beans by default).
     * Declared explicitly here anyway purely for interview-readability:
     * it makes visible, in one place, exactly which UserDetailsService
     * and PasswordEncoder Spring Security is really using under the hood
     * to check credentials, rather than that wiring being entirely
     * implicit "magic".
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }
}
