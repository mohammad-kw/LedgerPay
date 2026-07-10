package com.wallet.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Razorpay into the Spring application context:
 *   1. @EnableConfigurationProperties(RazorpayProperties.class) activates
 *      RazorpayProperties as a bean, bound from the "razorpay.*" keys in
 *      application.properties (same mechanism SecurityConfig uses for
 *      JwtProperties).
 *   2. The @Bean method below builds ONE shared RazorpayClient for the
 *      whole application.
 *
 * Why a single shared RazorpayClient bean (rather than "new
 * RazorpayClient(...)" every time we need it)? The client wraps an HTTP
 * connection pool (OkHttp) under the hood. Creating one per request would
 * waste resources and defeat connection reuse. Building it once as a
 * singleton bean means Spring hands the same, ready-to-use instance to
 * anything that needs it (here, RazorpayOrderService) via constructor
 * injection - the standard Spring way to manage a shared, expensive-to-
 * create collaborator.
 */
@Configuration
@EnableConfigurationProperties(RazorpayProperties.class)
public class RazorpayConfig {

    /**
     * The RazorpayClient is constructed with our (test-mode) key id +
     * secret, so every API call it makes is automatically authenticated as
     * our merchant account. Its constructor is declared to throw
     * RazorpayException (a checked exception); we let that propagate out of
     * this @Bean method, which means if the credentials are structurally
     * invalid the application fails fast AT STARTUP with a clear error,
     * rather than only discovering the problem on the first payment attempt.
     */
    @Bean
    public RazorpayClient razorpayClient(RazorpayProperties properties) throws RazorpayException {
        return new RazorpayClient(properties.keyId(), properties.keySecret());
    }
}
