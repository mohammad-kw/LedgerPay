/**
 * Authentication and authorization infrastructure (PROJECT_SPEC.md
 * Section 7 - Security Requirements). Implements "JWT (access token +
 * refresh token)" from Section 2:
 *
 *   - {@link com.wallet.security.JwtProperties}          - type-safe binding of the "jwt.*" settings in application.properties.
 *   - {@link com.wallet.security.JwtService}              - creates and verifies signed access tokens.
 *   - {@link com.wallet.security.UserPrincipal}           - adapts our User entity to Spring Security's UserDetails interface.
 *   - {@link com.wallet.security.CustomUserDetailsService} - looks up a User by email for Spring Security's login process.
 *   - {@link com.wallet.security.JwtAuthenticationFilter}  - runs on every request, authenticating it if a valid JWT is present.
 *   - {@link com.wallet.security.SecurityConfig}           - wires all of the above together (filter chain, password encoder, AuthenticationManager).
 *
 * Refresh tokens themselves are NOT JWTs - see
 * {@link com.wallet.entity.RefreshToken}'s javadoc for why they are
 * instead plain, database-backed opaque strings (this is what makes them
 * revocable, per Section 7).
 */
package com.wallet.security;

