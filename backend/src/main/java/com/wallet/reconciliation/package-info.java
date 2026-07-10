/**
 * The reconciliation job: comparing our local "transactions" table against
 * Razorpay's own payment records for a date range, and logging any
 * mismatches into ReconciliationLog rows (PROJECT_SPEC.md Section 3.5).
 *
 * Will contain a @Scheduled job (runs automatically once a day) as well as
 * the same logic exposed manually via
 * POST /api/admin/reconciliation/run (Section 5), so it can be triggered
 * on demand for demos/testing without waiting for the schedule.
 *
 * Empty for now - implemented in Phase 4 ("Reconciliation + Tests"), per
 * PROJECT_SPEC.md Section 10.
 */
package com.wallet.reconciliation;
