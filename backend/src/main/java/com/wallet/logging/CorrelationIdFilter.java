package com.wallet.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every incoming HTTP request a unique CORRELATION ID (a.k.a. request
 * id) and makes it available to EVERY log line produced while that request is
 * being handled - so a single transaction's full journey can be traced through
 * the logs (PROJECT_SPEC.md Section 9: "Structured logs with a correlation/
 * request ID so a single transaction's full journey can be traced").
 *
 * ── How it works (the interview explanation) ─────────────────────────────
 * SLF4J's MDC (Mapped Diagnostic Context) is a per-THREAD key/value map. In a
 * servlet container each request is handled on one thread, so anything we put
 * in the MDC at the start of the request is visible to every log statement
 * that thread makes afterwards. Our log PATTERN (see application.properties)
 * prints the MDC value %X{requestId}, so the id automatically appears on every
 * line - controllers, services, repositories - without any of that code
 * having to know about it.
 *
 * CRITICAL: because MDC is tied to a thread and servlet threads are POOLED and
 * REUSED across requests, we MUST clear it in a finally block. Otherwise the
 * next request handled by the same thread would inherit the previous request's
 * id - a classic, subtle logging bug.
 *
 * We also honour an inbound "X-Request-Id" header if the caller (or an
 * upstream proxy/load balancer) already set one, so a correlation id can span
 * multiple services; otherwise we generate a fresh short id. The id is echoed
 * back on the response header so a client can quote it when reporting an issue.
 *
 * @Order(HIGHEST_PRECEDENCE) makes this run before all other filters (e.g. the
 * JWT filter), so even logs produced during authentication carry the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** MDC key; must match %X{requestId} in the logging pattern. */
    public static final String MDC_KEY = "requestId";

    /** Header used to receive/propagate the id across services and back to the client. */
    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            // A short 8-char slice keeps logs readable while staying unique
            // enough for a single app's traffic.
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear - servlet threads are pooled and reused (see javadoc).
            MDC.remove(MDC_KEY);
        }
    }
}
