/**
 * Data Transfer Objects - plain Java {@code record}s that define the exact
 * JSON shape of API requests and responses, as listed in PROJECT_SPEC.md
 * Section 5.
 *
 * We deliberately never expose @Entity classes (com.wallet.entity)
 * directly as request/response bodies. Keeping DTOs separate from entities
 * matters for several concrete reasons:
 *   1. Security: an entity might carry fields we must never serialize to a
 *      client (e.g. User.passwordHash) - a DTO simply omits them.
 *   2. Validation: DTOs are where Bean Validation annotations (@NotNull,
 *      @Positive, @Email, ...) belong, since they describe constraints on
 *      the incoming wire format, not on how data is stored.
 *   3. Stability: the database schema can evolve independently of the
 *      public API contract, and vice versa.
 *
 * The auth DTOs ({@link com.wallet.dto.RegisterRequest},
 * {@link com.wallet.dto.RegisterResponse},
 * {@link com.wallet.dto.LoginRequest}, {@link com.wallet.dto.RefreshRequest},
 * {@link com.wallet.dto.AuthResponse}) are implemented as of this phase.
 * Wallet/transaction DTOs are implemented alongside their endpoints in
 * later phases, per PROJECT_SPEC.md Section 10's phase-by-phase build plan.
 */
package com.wallet.dto;

