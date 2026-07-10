package com.wallet.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for the "jwt.*" properties in application.properties
 * (e.g. jwt.secret, jwt.access-token-expiry-ms).
 *
 * Why a @ConfigurationProperties class instead of scattering
 * @Value("${jwt.secret}") annotations across JwtService directly?
 *   1. Type safety: Spring converts and validates the property types
 *      ONCE, here, instead of at every individual injection point.
 *   2. Single source of truth: every class that needs a JWT setting
 *      depends on this one small class instead of repeating the same
 *      raw property key string (which is easy to typo) in multiple
 *      places.
 *   3. IDE support: typing "jwtProperties." gives autocomplete for every
 *      available setting.
 *
 * This class needs no @Component/@Service annotation itself -
 * @EnableConfigurationProperties(JwtProperties.class) on SecurityConfig
 * (see below) is what registers it as a Spring bean.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

        /**
         * The raw secret key material used to sign and verify every access
         * token's HMAC-SHA256 signature. Whoever holds this value can forge
         * valid access tokens for ANY user, so it must be kept out of
         * source control in any real deployment (see application.properties
         * for how the ${JWT_SECRET} environment variable placeholder works,
         * and PROJECT_SPEC.md Section 7).
         */
        String secret,

        /** How many milliseconds an access token remains valid after being issued. Section 2: "short-lived (e.g. 15 min)". */
        long accessTokenExpiryMs,

        /** How many milliseconds a refresh token remains valid after being issued. Section 2: "longer-lived". */
        long refreshTokenExpiryMs
) {
}
