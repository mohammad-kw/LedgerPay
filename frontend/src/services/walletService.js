import api from "./api";

/**
 * Thin wrapper functions around the read-only wallet endpoints
 * (PROJECT_SPEC.md Section 5). Like authService.js, these are plain
 * functions with no React code - they just call the backend and
 * return/throw. The shared `api` instance (see api.js) automatically
 * attaches the JWT access token and transparently refreshes it on a 401,
 * so nothing here has to think about auth.
 */

/**
 * GET /api/wallet/balance
 * Returns { walletId, balance, currency } for the logged-in user's wallet.
 * `balance` comes back as a string like "0.00" (the backend serializes
 * BigDecimal that way to preserve exact precision) - format it for display,
 * don't rely on it being a JS number.
 */
export function getBalance() {
  return api.get("/wallet/balance").then((res) => res.data);
}

/**
 * GET /api/wallet/transactions?page=&size=&status=
 * Returns a PageResponse:
 *   { content: [...], page, size, totalElements, totalPages, last }
 * where each item in `content` is
 *   { id, type, status, amount, currency, direction, razorpayOrderId, createdAt }.
 *
 * All params are optional. `status`, when provided, must be one of the
 * backend TransactionStatus values (CREATED, PENDING, SUCCESS, FAILED,
 * REVERSED); we simply omit it from the query string when it's falsy so the
 * backend returns all statuses.
 */
export function getTransactions({ page = 0, size = 20, status } = {}) {
  const params = { page, size };
  if (status) {
    params.status = status;
  }
  return api.get("/wallet/transactions", { params }).then((res) => res.data);
}

/**
 * POST /api/wallet/topup/initiate
 *
 * Step ONE of the Razorpay top-up flow (PROJECT_SPEC.md Section 5): ask the
 * backend to create a Razorpay order + a local transaction (status CREATED).
 * It returns everything Razorpay Checkout.js needs to open the payment popup:
 *   { transactionId, razorpayOrderId, razorpayKeyId, amountInPaise, currency }
 *
 * IMPORTANT - this does NOT mean the money has arrived. It only creates the
 * order. Actual crediting happens later, server-side, when Razorpay's signed
 * webhook confirms the payment (Phase 3). So the caller must treat a
 * successful response here as "order created, popup can open", not "paid".
 *
 * Idempotency (PROJECT_SPEC.md Section 3.1): we generate a fresh UUID per
 * call and send it as the `Idempotency-Key` HEADER (not in the body). If the
 * same key were ever retried, the backend returns the ORIGINAL order instead
 * of creating a duplicate. crypto.randomUUID() is built into all modern
 * browsers and needs no library.
 *
 * @param {number|string} amount - rupee amount to add (e.g. 500 or "500.00")
 * @returns the backend TopUpInitiateResponse described above
 */
export function initiateTopUp(amount) {
  const idempotencyKey = crypto.randomUUID();
  return api
    .post(
      "/wallet/topup/initiate",
      { amount },
      { headers: { "Idempotency-Key": idempotencyKey } },
    )
    .then((res) => res.data);
}

/**
 * POST /api/wallet/transfer
 *
 * Send money from the logged-in user's wallet to another user by email
 * (PROJECT_SPEC.md Section 5). Unlike top-up, this completes synchronously:
 * on success the backend has ALREADY moved the money (written the double-entry
 * ledger and updated both balances) inside one DB transaction, so the response
 *   { transactionId, status, receiverEmail, amount, currency, senderBalanceAfter }
 * reflects a completed transfer. `senderBalanceAfter` is the caller's new
 * balance, handy for updating the UI immediately.
 *
 * Idempotency (Section 3.1): as with top-up we generate a fresh UUID and send
 * it as the `Idempotency-Key` HEADER. If the exact same request is retried
 * with the same key, the backend returns the original result rather than
 * transferring twice.
 *
 * Possible error responses the caller should handle:
 *   400 - invalid transfer (e.g. sending to yourself, unknown recipient, or
 *         a malformed body)
 *   422 - insufficient balance
 * In all error cases the message is in err.response.data.message.
 *
 * @param {string} receiverEmail - the recipient's account email
 * @param {number|string} amount - rupee amount to send
 * @returns the backend TransferResponse described above
 */
export function sendMoney(receiverEmail, amount) {
  const idempotencyKey = crypto.randomUUID();
  return api
    .post(
      "/wallet/transfer",
      { receiverEmail, amount },
      { headers: { "Idempotency-Key": idempotencyKey } },
    )
    .then((res) => res.data);
}

/**
 * POST /api/wallet/topup/mock  (DEV / DEMO ONLY)
 *
 * Instantly credit the wallet WITHOUT going through Razorpay. This exists so
 * the app can be demoed end-to-end while the Razorpay account's KYC/activation
 * is still pending. The backend route only EXISTS when the server is started
 * with MOCK_TOPUP_ENABLED=true (it's @ConditionalOnProperty); otherwise this
 * call gets a 404.
 *
 * Despite bypassing Razorpay, the backend still runs the SAME correctness core
 * as the real webhook: it writes a double-entry CREDIT ledger row, updates the
 * cached balance, and drives the transaction CREATED -> SUCCESS via the state
 * machine, all in one DB transaction. So the response
 *   { transactionId, status, amount, currency, balanceAfter }
 * reflects a completed, already-credited top-up.
 *
 * Idempotency (Section 3.1): fresh UUID sent as the Idempotency-Key header, so
 * a retry with the same key won't double-credit.
 *
 * @param {number|string} amount - rupee amount to add
 * @returns the backend MockTopUpResponse described above
 */
export function mockTopUp(amount) {
  const idempotencyKey = crypto.randomUUID();
  return api
    .post(
      "/wallet/topup/mock",
      { amount },
      { headers: { "Idempotency-Key": idempotencyKey } },
    )
    .then((res) => res.data);
}
