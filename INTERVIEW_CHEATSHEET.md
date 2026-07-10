# LedgerPay — 1-Page Cheat Sheet

**Pitch:** A digital wallet (mini-Paytm) in **Spring Boot + React**. Add money via **Razorpay**, send money, view history. Real backend depth: **idempotency, double-entry ledger, state machine, signed webhooks, daily reconciliation**. 52 tests, CI, RBAC admin dashboard, correlation-ID logging.

---

## The 5 Core Concepts (know cold)

1. **Idempotency** — client sends a unique `Idempotency-Key` header per action; server dedupes via `findByIdempotencyKey` + DB unique constraint. A double-click never charges twice. _Header (not body) because it's a property of the request; DB constraint closes the race._

2. **Double-entry ledger** — every transaction = balanced **DEBIT + CREDIT** that net to **zero**; each entry stores `balance_after`. Wallet `balance` is a **cached/derived** value updated atomically with the ledger. Fully auditable. _Money = `BigDecimal`, never float (0.1+0.2≠0.3)._

3. **State machine** — `CREATED→PENDING→SUCCESS`/`FAILED`, `SUCCESS→REVERSED`. Illegal jumps (e.g. `CREATED→SUCCESS`) rejected in code → can't skip verification. FAILED/REVERSED are terminal.

4. **Webhooks = source of truth** — never trust the browser redirect. Razorpay calls `/api/webhooks/razorpay`; verify `X-Razorpay-Signature` (HMAC-SHA256 of raw body w/ webhook secret) before crediting. Duplicates ignored via unique `razorpay_event_id`.

5. **Reconciliation (the differentiator)** — daily **read-only** job compares local TOPUPs vs gateway payments (join on payment id), walks **both directions**, logs 4 mismatch types: `MISSING_LOCALLY`, `MISSING_AT_GATEWAY`, `STATUS_MISMATCH`, `AMOUNT_MISMATCH`. **Detects, never auto-fixes.** Reconciles _yesterday_ (settled). Built against a **gateway interface** → swap simulated↔real Razorpay via one config line.

---

## Security

- **JWT:** access token (15 min, stateless, signed) + refresh token (7 days, opaque, in DB, **revocable + rotated**). Claims: `sub, uid, role` — signed not encrypted, no secrets inside.
- **BCrypt** password hash (deliberately slow). **RBAC:** `role` USER/ADMIN, `/api/admin/**` → `hasRole("ADMIN")`; no "register as admin" (seeded from env).
- **IDOR-safe:** ids never taken from client; `/transactions/{id}` → 404 if not yours. CSRF off (bearer tokens, not cookies). Secrets in env vars.

## Architecture

- **Controller (thin) → Service (`@Transactional`, logic) → Repository (Spring Data) → Entity.** **DTOs** on the wire (can't leak `passwordHash`; decouples API from schema).
- `@Transactional` = atomicity (register = User+Wallet both or neither).

## Testing (52)

Unit (Mockito, no DB): state machine, transfer idempotency/ledger, webhook signature, reconciliation 4 types. Integration (H2): full top-up + transfer flows, duplicate handling.

## Frontend

React + Vite + Router. `AuthContext` + `ProtectedRoute(requireAdmin)`. **Axios interceptors**: attach token; on 401 → one silent refresh → retry. Admin dashboard: Recharts (status pie, type bar) + reconciliation panel + global feed.

## Ops

GitHub Actions CI (tests + build). `@Scheduled` daily recon 02:00. `@ConditionalOnProperty` feature flags. **Correlation ID:** `CorrelationIdFilter` puts per-request id in **MDC** → `%X{requestId}` on every log line; cleared in `finally` (pooled threads).

---

## Bugs I Hit (challenge stories)

- **Razorpay 401** → account not activated (KYC). Diagnosed via direct API probe → built **mock top-up** (reuses real ledger/state-machine) + gateway abstraction. External dependency never blocked the core work.
- **Normal user got admin** → _data_ bug: `ddl-auto=update` added `role` column but didn't backfill existing rows with default. Fixed rows + added DB-level `DEFAULT 'USER'`. (Why real apps use Flyway.)
- **"Transfer broken"** → non-bug: sending to unregistered email = correct 400. _Reproduce before fixing._

## Conscious Trade-offs

`ddl-auto=update` over Flyway (convenience) · cached balance + ledger over pure event sourcing · simulated gateway over blocking on KYC · one ADMIN role over full hierarchy · sync MVC over reactive · no Redis/rate-limit (DB constraint covers idempotency).

## With more time

Flyway migrations · rate-limiting · withdrawal flow · Testcontainers · real Razorpay after activation.

---

## Instant answers

- **Add money flow:** initiate (Idempotency-Key) → Razorpay order + txn CREATED → Checkout → signed webhook → verify → CREATED→PENDING→SUCCESS + ledger CREDIT + balance, all `@Transactional`.
- **Why access+refresh?** short access limits theft window; refresh revocable/rotated for staying logged in.
- **Why DTOs?** no `passwordHash` field = can't leak it; decouples API from DB.
- **Webhook missed?** stays PENDING; reconciliation flags it.
- **Role in JWT safe?** UI routing only; server re-checks every admin request.
