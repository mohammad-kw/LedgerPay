package com.wallet.exception;

/**
 * Thrown when a call to an EXTERNAL payment gateway (Razorpay) fails - for
 * example, creating an order errors out, times out, or the gateway is
 * unreachable.
 *
 * Why its own exception type instead of a plain RuntimeException? Because
 * the CAUSE matters for the HTTP status we return. A generic unhandled
 * exception maps to 500 Internal Server Error, which means "WE broke". But
 * a Razorpay outage isn't our bug - our code is fine, an upstream
 * dependency we rely on failed. The honest, correct status for that is
 * 502 Bad Gateway ("I'm a healthy server, but the upstream server I needed
 * gave me an invalid/failed response"). Distinguishing the two matters
 * operationally: a spike of 502s points you at Razorpay/network, a spike of
 * 500s points you at your own code.
 *
 * GlobalExceptionHandler maps this to 502 Bad Gateway.
 *
 * See DuplicateEmailException's javadoc for why this extends
 * RuntimeException rather than a checked Exception.
 */
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
