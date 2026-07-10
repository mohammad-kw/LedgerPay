/**
 * Business logic ("service layer"). Classes here (typically annotated
 * @Service) implement the actual rules of the domain: creating a wallet
 * when a user registers, validating an idempotency key before creating a
 * transaction, applying double-entry ledger updates, enforcing the
 * transaction state machine's legal transitions (PROJECT_SPEC.md
 * Section 3), etc.
 *
 * Controllers call services; services call repositories. Services are
 * where @Transactional boundaries are declared, since a single logical
 * operation (e.g. "register a user AND create their wallet", or later,
 * "transfer money") must atomically touch multiple tables - either all of
 * it commits, or none of it does. See
 * {@link com.wallet.service.AuthService#register}'s javadoc for a
 * concrete, worked example of why this matters.
 *
 * {@link com.wallet.service.AuthService} (register/login/refresh) is
 * implemented as of this phase. Wallet/transfer/reconciliation logic is
 * implemented in later phases, per PROJECT_SPEC.md Section 10's
 * phase-by-phase build plan.
 */
package com.wallet.service;

