/**
 * REST API controllers (the "C" in the web layer) - classes annotated
 * @RestController that define HTTP endpoints like those listed in
 * PROJECT_SPEC.md Section 5 (e.g. POST /api/auth/register,
 * GET /api/wallet/balance).
 *
 * Controllers in this project should stay "thin": they parse/validate the
 * incoming HTTP request (with help from DTOs in com.wallet.dto and
 * Bean Validation annotations), delegate all real work to a class in
 * com.wallet.service, and translate the result back into an HTTP response.
 * No JDBC/JPA/business logic should live directly in a controller.
 *
 * {@link com.wallet.controller.AuthController} (register/login/refresh)
 * is implemented as of this phase. Wallet/webhook/admin endpoints are
 * implemented in later phases, per PROJECT_SPEC.md Section 10's
 * phase-by-phase build plan.
 */
package com.wallet.controller;

