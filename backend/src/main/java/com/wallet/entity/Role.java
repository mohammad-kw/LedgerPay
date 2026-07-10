package com.wallet.entity;

/**
 * Backs the "role" VARCHAR(20) column on the "users" table - the single
 * differentiator between an ordinary user and a privileged administrator.
 *
 * Using a real Java enum (rather than a raw String or a boolean like
 * {@code isAdmin}) means:
 *   - Only these exact values can ever be stored - typos won't compile.
 *   - It leaves room to grow (e.g. a future SUPPORT or AUDITOR role) without
 *     the "add another boolean column each time" mess a boolean would cause.
 *
 * Spring Security convention: authorities are prefixed with "ROLE_" (e.g.
 * ROLE_ADMIN). We store the BARE name here (ADMIN / USER) and add the
 * "ROLE_" prefix only where Spring needs it (see UserPrincipal.getAuthorities),
 * keeping the persisted value clean.
 */
public enum Role {
    /** A normal end user: can top up, transfer, view their own wallet. The default for everyone who self-registers. */
    USER,

    /** A privileged operator: can additionally reach the /api/admin/** endpoints (metrics, reconciliation, global transaction feed). Never self-assignable - see AdminSeeder / SecurityConfig. */
    ADMIN
}
