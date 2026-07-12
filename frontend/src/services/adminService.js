import api from "./api";

/**
 * Thin wrappers around the admin-only backend endpoints (all under
 * /api/admin/**, which the backend restricts to ROLE_ADMIN). These are plain
 * functions with no React code, so any component can call them.
 *
 * If a non-admin somehow calls these, the backend returns 403 and the promise
 * rejects - the UI never gets the data. The role check on the frontend is only
 * to decide what to SHOW; the backend is the real gate.
 */

/** GET /api/admin/metrics -> aggregate counts/sums + chart data. */
export function getMetrics() {
  return api.get("/admin/metrics").then((res) => res.data);
}

/** GET /api/admin/transactions?limit= -> global recent-transaction feed. */
export function getRecentTransactions(limit = 50) {
  return api
    .get("/admin/transactions", { params: { limit } })
    .then((res) => res.data);
}

/** GET /api/admin/reconciliation/logs -> past reconciliation runs (newest first). */
export function getReconciliationLogs() {
  return api.get("/admin/reconciliation/logs").then((res) => res.data);
}

/**
 * POST /api/admin/reconciliation/run?date=YYYY-MM-DD -> trigger a run.
 * Omit `date` to reconcile yesterday (the backend default).
 */
export function runReconciliation(date) {
  const params = date ? { date } : {};
  return api
    .post("/admin/reconciliation/run", null, { params })
    .then((res) => res.data);
}

/** GET /api/admin/users -> every user with wallet balance + transaction count. */
export function getUsers() {
  return api.get("/admin/users").then((res) => res.data);
}

/** GET /api/admin/users/{id} -> one user's profile + full transaction history. */
export function getUserDetail(id) {
  return api.get(`/admin/users/${id}`).then((res) => res.data);
}
