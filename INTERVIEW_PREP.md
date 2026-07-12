# LedgerPay — Complete Interview Preparation Guide

> **How to use this file:** Read it top-to-bottom once, then use the Q&A sections to self-test. Every design decision includes _why we chose it_, _what we rejected_, and _the trade-off_ — that is exactly what interviewers drill into. If you can say each answer out loud in your own words, you're ready.

---

## PART 1 — THE 30-SECOND PITCH (memorize this)

> "LedgerPay is a digital wallet — like a mini-Paytm — built with **Spring Boot and React**. Users register, add money via **Razorpay** (test mode), send money to each other, and see their history. What makes it more than a CRUD app is the **financial-correctness engineering**: **idempotency** so a double-click never charges twice, a **double-entry ledger** so every balance is auditable, a **transaction state machine** so payments can't skip verification, **webhook-based confirmation** with signature verification instead of trusting the browser, and a **daily reconciliation job** that compares our records against the payment gateway to catch drift. It's fully tested (52 tests), has CI, role-based admin dashboard, and structured logging with correlation IDs."

**Why this project exists:** Most junior portfolios are generic CRUD or chatbot clones. This demonstrates real backend depth (money correctness, idempotency, webhooks, reconciliation) that most 1-YOE candidates can't show.

---

## PART 2 — TECH STACK & WHY

| Layer     | Choice                                     | Why this / why not alternatives                                                                                                                                                     |
| --------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Backend   | **Java 21 + Spring Boot 3.3.4**            | Industry-standard for fintech backends; strong typing catches money bugs at compile time.                                                                                           |
| Web       | Spring Web (MVC)                           | Mature, synchronous model is simpler to reason about than WebFlux/reactive — and our load is portfolio-scale, so reactive's complexity isn't justified.                             |
| Data      | Spring Data JPA + Hibernate                | Removes boilerplate SQL; lets us swap MySQL↔PostgreSQL by changing only the dialect. **Trade-off:** less control than raw JDBC/MyBatis, and you must understand lazy loading / N+1. |
| DB (dev)  | **MySQL 8**                                | Ubiquitous, free, what I'd used before.                                                                                                                                             |
| DB (test) | **H2 in-memory** (MySQL mode)              | Tests run instantly anywhere, no server needed, and can never touch real data.                                                                                                      |
| Auth      | **JWT** (access + refresh)                 | Stateless — server doesn't store sessions, scales horizontally.                                                                                                                     |
| Payments  | **Razorpay test mode**                     | Free, no KYC for test mode, I had prior internship experience with it.                                                                                                              |
| Frontend  | **React 18 + Vite + React Router + Axios** | Vite = fast dev/build; Axios interceptors handle token attach/refresh cleanly.                                                                                                      |
| Charts    | **Recharts**                               | Simple declarative React charts for the admin dashboard.                                                                                                                            |
| Testing   | **JUnit 5, Mockito, AssertJ**              | Standard Java testing trio.                                                                                                                                                         |
| CI        | **GitHub Actions**                         | Free, runs tests on every push.                                                                                                                                                     |

**Likely Q: "Why Spring Boot over Node/Express?"**
A: Java's static typing and BigDecimal are safer for money; Spring's transaction management (`@Transactional`) and mature security ecosystem fit a financial app. Node is fine but I wanted to show backend depth in a strongly-typed, transactional environment.

---

## PART 3 — THE FIVE CORE CONCEPTS (the heart of every interview)

These five are _the whole point of the project_. Know them cold.

### 3.1 Idempotency

**The problem:** A user double-clicks "Send", or the network retries a request — money must NOT move twice.

**Our solution:** The client generates a unique **`Idempotency-Key`** (a UUID) per logical action and sends it as an HTTP **header**. Before creating a transaction, the server checks `findByIdempotencyKey(key)`. If it already exists, we **return the original result** instead of creating a new one. The key has a `UNIQUE` constraint in the DB as a hard backstop.

**Why a header, not the body?** Idempotency is a property of the HTTP _request_, not the business payload — this matches Stripe/Razorpay convention.

**Why client-generated?** Only the client knows "this is a retry of the same intent." A server-generated id can't dedupe retries.

**What else could we use?**

- Idempotency via a natural unique key (e.g. Razorpay order id) — but that only exists _after_ creating the order; the client key covers the whole request.
- A distributed cache (Redis) with the key + TTL — better at huge scale, but overkill here; the DB unique constraint is simpler and durable.

**Trade-off:** Keys live forever in our table (no TTL cleanup). Fine at this scale; at large scale you'd expire old keys.

**Interview curveball — "What if two identical requests hit at the exact same millisecond?"**
A: The DB `UNIQUE` constraint on `idempotency_key` is the final guard — the second insert fails, and we handle that as "already processed." The check-then-insert race is closed by the constraint, not just the `if` check.

### 3.2 Double-entry ledger

**The problem:** If balance is just a single number you edit (`balance = balance - 100`), you can't audit _why_ a balance is what it is, and a bug can silently corrupt it.

**Our solution:** Every transaction writes **ledger entries** — a **DEBIT** from one wallet and a **CREDIT** to another — that must **net to zero**. Each entry stores `amount` and `balance_after` (a running snapshot). The wallet's `balance` column is a **cached/derived** convenience value; the ledger is the source of truth.

**Example — transfer ₹200 Alice→Bob:**

- DEBIT ₹200 on Alice's wallet (balance_after = her new balance)
- CREDIT ₹200 on Bob's wallet (balance_after = his new balance)
- Sum of entries for this transaction = 0 ✅

**Why store `balance_after`?** So you can reconstruct history and audit each step without re-summing the entire ledger every time.

**Why keep a cached `balance` on the wallet at all?** Reads (show balance) are frequent; recomputing from all ledger rows each time is wasteful. We update the cache inside the same transaction that writes the ledger, so they never disagree.

**What else could we use?**

- Single mutable balance only — simplest but **not auditable** (rejected; the whole point is traceability).
- Event sourcing (rebuild balance purely from events) — pure but complex; our hybrid (ledger + cached balance) is the pragmatic middle.

**Trade-off:** The cached balance _could_ drift from the ledger if a bug updates one but not the other — which is exactly why **reconciliation** and `@Transactional` atomicity matter.

**Interview: "Why BigDecimal, never float/double for money?"**
A: `double` is binary floating point — `0.1 + 0.2 != 0.3`. Rounding errors accumulate and are unacceptable for money. `BigDecimal` does exact base-10 arithmetic with defined scale/rounding. The DB column is `DECIMAL(15,2)`.

### 3.3 Transaction state machine

**States:** `CREATED → PENDING → SUCCESS` or `→ FAILED`; `SUCCESS → REVERSED` (refund).

**The rule that matters:** you must **never** jump `CREATED → SUCCESS` directly (that would mean crediting a wallet without verifying the payment). Illegal transitions are rejected in code.

**Why enforce it in code, not just document it?** A guard method validates each transition; an illegal one throws. This makes "skipping verification" structurally impossible, which is the exact bug the design warns against.

**Terminal states:** `FAILED` and `REVERSED` — nothing can transition out of them.

**What else could we use?** A full workflow engine (e.g. Spring StateMachine library) — overkill; a small validated enum + guard method is clearer and testable.

### 3.4 Webhooks as source of truth (not the browser redirect)

**The problem:** After Razorpay checkout, the browser is redirected back to our app. **We must not trust that redirect** to mark payment success — a malicious user could forge that call and credit themselves.

**Our solution:** The real confirmation is Razorpay calling our server **directly** at `POST /api/webhooks/razorpay`. We **verify the `X-Razorpay-Signature` header** (HMAC-SHA256 of the raw body using our webhook secret) before trusting anything. Only a verified `payment.captured` webhook moves the transaction to SUCCESS and credits the wallet.

**Why HMAC?** Only we and Razorpay know the webhook secret. If the signature recomputed on our side matches the header, the payload is authentic and untampered. A forged call has no valid signature → rejected.

**Duplicate webhooks:** Razorpay may deliver the same event more than once. We store `razorpay_event_id` with a `UNIQUE` constraint and check it — reprocessing is a no-op (idempotent webhook handling).

**Why store the raw payload (`webhook_events` table)?** Audit + replay + debugging. If processing fails, we still have the exact event.

**Interview: "Why not just call Razorpay's verify API from the browser callback?"**
A: You _can_ do server-to-server verification as a backup, but the webhook is the authoritative, out-of-band confirmation that doesn't depend on the user's browser completing anything. Trusting the browser alone is the classic payment security hole.

### 3.5 Reconciliation (THE differentiator)

**The problem:** Webhooks can be missed, dropped, or arrive out of order; our server can crash mid-processing. Any of these silently desyncs our DB from the gateway.

**Our solution:** A **daily job** (also manually triggerable) that, for a given day, compares our local TOPUP transactions against the gateway's payment records and **logs every mismatch** to `reconciliation_logs`. It's a **safety net**, run **read-only** — it _detects_ drift, it does **not** auto-fix balances (a human reviews flagged rows; auto-correcting money from a batch job is dangerous).

**The join key:** the gateway payment id (`razorpay_payment_id`). We build a map of local top-ups and a map of gateway payments for the day, then **walk both directions** (a full outer join):

| Mismatch type        | Meaning                                                                          |
| -------------------- | -------------------------------------------------------------------------------- |
| `MISSING_LOCALLY`    | Gateway has a payment we never recorded (most serious — money moved, no record). |
| `MISSING_AT_GATEWAY` | We have a local SUCCESS top-up the gateway didn't return.                        |
| `STATUS_MISMATCH`    | We say SUCCESS, gateway says failed/refunded (or vice versa).                    |
| `AMOUNT_MISMATCH`    | Both succeeded but amounts differ.                                               |

**Why only TOPUPs?** Only top-ups go through the gateway. Transfers/withdrawals are internal — the gateway never sees them, so including them would produce false "missing at gateway" mismatches.

**Why reconcile _yesterday_, not today?** A day's payments are only fully settled once it's over; reconciling mid-day would flag in-flight payments as false mismatches.

**THE KEY DESIGN — Gateway abstraction (my strongest talking point):**
The reconciliation engine depends only on an **interface** `PaymentGatewayReconciliationSource`, with two implementations:

- `SimulatedGatewaySource` (default) — derives a plausible gateway view with deterministic drift, so I can demo all four mismatch types **without an activated Razorpay account**.
- `RazorpayGatewaySource` — calls the real Razorpay Payments API.

Switching is a **one-line config change** (`app.reconciliation.gateway-provider`), **zero engine changes**. The interview line: _"I designed reconciliation against a gateway abstraction, so the comparison logic is independent of and testable without the external provider."_

---

## PART 4 — DATABASE SCHEMA (know each table's purpose)

| Table                 | Purpose                  | Key columns                                                                                                                                  |
| --------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `users`               | Registered accounts      | `email` (unique, = username), `password_hash` (BCrypt), `role` (USER/ADMIN)                                                                  |
| `wallets`             | One per user             | `balance` (cached, derived), `currency`, `user_id` (unique)                                                                                  |
| `transactions`        | Top-ups & transfers      | `idempotency_key` (unique), `type`, `status`, `sender_wallet_id`, `receiver_wallet_id`, `amount`, `razorpay_order_id`, `razorpay_payment_id` |
| `ledger_entries`      | Double-entry bookkeeping | `entry_type` (DEBIT/CREDIT), `amount`, `balance_after`                                                                                       |
| `webhook_events`      | Raw Razorpay events      | `razorpay_event_id` (unique), `payload_json`, `signature_verified`, `processed`                                                              |
| `reconciliation_logs` | Audit of each recon run  | `run_date`, `total_checked`, `mismatches_found`, `mismatch_details_json`, `status`                                                           |

**Wallet column usage by transaction type:**

- TOPUP: sender = null, receiver = the user (money enters from Razorpay)
- TRANSFER: sender + receiver both set
- WITHDRAWAL: sender = user, receiver = null (money leaves) — _enum exists but no endpoint built (documented future work)_

**Why `@Enumerated(EnumType.STRING)` not ORDINAL?** Ordinal stores 0/1/2 — if you reorder the enum, every existing row silently means something different. STRING stores the readable name and is reorder-safe.

**Why `@CreationTimestamp` (Java-side) instead of DB `DEFAULT CURRENT_TIMESTAMP`?** Portability (identical on MySQL/PostgreSQL) and the value is visible on the Java object immediately after save without a re-fetch.

**Why NOT Lombok `@Data` on entities?** `@Data` generates `equals`/`hashCode` based on all fields — dangerous for JPA entities (id is null before save; Hibernate proxies break value equality; objects can "vanish" from a HashSet when id changes). We use `@Getter/@Setter/@Builder` and keep Java's default identity equality.

---

## PART 5 — API ENDPOINTS

**Auth:** `POST /api/auth/register`, `/login`, `/refresh`
**Wallet:** `GET /api/wallet/balance`, `GET /api/wallet/transactions?page=&status=`, `GET /api/wallet/transactions/{id}`, `POST /api/wallet/topup/initiate`, `POST /api/wallet/transfer`
**Webhook:** `POST /api/webhooks/razorpay` (called by Razorpay, not the browser)
**Admin:** `GET /api/admin/metrics`, `GET /api/admin/transactions`, `POST /api/admin/reconciliation/run`, `GET /api/admin/reconciliation/logs`
**Health:** `GET /actuator/health`
**Dev-only:** `POST /api/wallet/topup/mock` (mock top-up — see Part 9)

---

## PART 6 — SECURITY (JWT, RBAC, hashing)

### JWT design

- **Access token:** short-lived (**15 min**), a signed JWT. Sent as `Authorization: Bearer <token>` on every request. **Stateless** — verified by signature alone, no DB lookup.
- **Refresh token:** longer-lived (**7 days**), an opaque random UUID stored in the DB (revocable). Used only at `/api/auth/refresh`.
- **Refresh token rotation:** every refresh **revokes the old token and issues a new one**. If a refresh token is stolen, it works only once before the real user's next refresh invalidates it.

**Why access token stateless but refresh token in DB?** Access tokens must be fast (checked on every request) → self-contained signature. Refresh tokens must be **revocable** (logout, theft) → stored so we can invalidate them. This is the standard trade-off.

**What's inside the JWT?** `sub` (email), `uid` (user id), `role`, `iat`, `exp`. **Never** secrets/passwords — a JWT is signed, **not encrypted**; anyone can Base64-decode the payload.

**Why is putting `role` in the JWT safe?** It's used only for **client-side routing** (show admin UI or not). The **server re-checks the role on every `/api/admin` request** against `hasRole("ADMIN")` — a tampered token can't actually grant access because tampering breaks the signature.

### Password hashing

- **BCrypt**, never plain text. BCrypt is deliberately **slow** (work factor) to make brute-forcing stolen hashes expensive. SHA-256 is a _bad_ choice for passwords precisely because it's fast.

### RBAC (role-based access control)

- One `role` column, values `USER` / `ADMIN`.
- `UserPrincipal.getAuthorities()` maps role → `ROLE_USER` / `ROLE_ADMIN`.
- `SecurityConfig`: `/api/admin/**` requires `hasRole("ADMIN")`; anonymous → 401, non-admin → 403.
- **No "register as admin" endpoint** (that would be a privilege-escalation hole). The first admin is **seeded on startup** from env vars (`AdminSeeder`, a `CommandLineRunner`) — a trusted, out-of-band channel.

**Admin user-management view (oversight):** `GET /api/admin/users` lists every user (with wallet balance + transaction count); `GET /api/admin/users/{id}` returns one user's profile + full transaction history. Both are ADMIN-only and read-only.

**Q: How do you make sure the admin user view can't leak passwords?**
A: The response is a purpose-built DTO (`AdminUserResponse`) that has **no password field at all** — so it's *structurally impossible* to serialize the BCrypt hash, even by accident. Same "a DTO can't leak what it doesn't have" principle used everywhere in this codebase, instead of relying on remembering to strip a field.

**Q: The user-facing transaction endpoint returns 404 for someone else's transaction, but the admin endpoint returns a plain 404 for a missing user — why the difference?**
A: Different threat models. For a *normal* user, `GET /transactions/{id}` returns 404 (not 403) for a transaction they don't own, so ids can't be probed (IDOR/enumeration defense). For the *admin* endpoint, the admin is legitimately authorized to see every user, so hiding existence adds nothing — a plain 404 for an unknown id is correct. The lesson: "hide existence" is only worth it when the caller *shouldn't* be able to know the resource exists.

### Other security

- All secrets in **environment variables** (`.env`, gitignored); `.env.example` is the committed template.
- **Bean Validation** (`@Valid`) on every request body — see the dedicated subsection below.
- Webhook signature verified on every call.
- **IDOR defense:** endpoints never take a wallet/user id from the client — they use the authenticated user's id from the JWT. `GET /transactions/{id}` returns 404 (not 403) for a transaction you don't own, so ids can't be probed.
- **Stateless session** (`SessionCreationPolicy.STATELESS`), **CSRF disabled** (safe because we use bearer tokens, not cookies — a malicious site can't silently attach a bearer header).

**Likely Q: "Why disable CSRF?"**
A: CSRF attacks rely on the browser auto-attaching credentials (cookies). We use an explicit `Authorization` header that a third-party site cannot set on a victim's behalf, and we hold no session. So CSRF protection adds friction for zero benefit here.

### Input validation — exactly what's enforced

**Two layers:** (1) **DTO-level** Jakarta Bean Validation (`@Valid` on the controller → fails become 400 via `GlobalExceptionHandler`, before any business code runs); (2) **service-level** business rules the annotations can't express.

| Field               | DTO rules (400 if violated)                                                                                               |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Register — name     | `@NotBlank`, `@Size(max=100)`                                                                                             |
| Register — email    | `@NotBlank`, `@Email` (format), `@Size(max=150)`                                                                          |
| Register — password | `@NotBlank`, `@Size(min=8,max=72)`, `@Pattern` requiring **≥1 upper, ≥1 lower, ≥1 digit**                                 |
| Register — phone    | optional; `@Size(max=15)`, `@Pattern` = **empty or 10–15 digits**                                                         |
| Login               | email `@NotBlank`+`@Email`, password `@NotBlank`                                                                          |
| Transfer            | receiverEmail `@NotBlank`+`@Email`; amount `@NotNull`, `@DecimalMin("1.00")`, `@DecimalMax("100000.00")`, `@Digits(13,2)` |
| Top-up              | amount: same min/max/decimals as transfer                                                                                 |

**Business-layer checks (not annotations):** duplicate email (`existsByEmail`), sufficient balance (`compareTo`), no self-transfer, idempotency-key dedupe.

**Why password max is 72:** BCrypt silently ignores bytes past 72 — accepting longer would mislead the user into thinking their full password counts.

**Why max is `@DecimalMax`, not a business rule:** it's a sanity bound against typos (a slipped digit can't create a ₹10-crore order); real per-user/daily limits would be a business rule in the service.

**Honest gaps I'd name if pushed:** no password _symbol_ requirement or breach-list check (would add zxcvbn/HaveIBeenPwned in prod); no per-transaction/daily transfer cap beyond the field max; phone isn't checked for real reachability (that's what an SMS OTP would prove — see the security roadmap).

---

## PART 7 — CORRELATION ID / STRUCTURED LOGGING

**Problem:** Concurrent requests interleave their logs; you can't trace one transaction's journey.

**Solution:** `CorrelationIdFilter` (runs first, `@Order(HIGHEST_PRECEDENCE)`) assigns each request a short id, stores it in SLF4J **MDC** (a per-thread key/value map), and the log pattern prints `%X{requestId}` on every line. So every log from controller→service→repository for one request shares one id.

**Critical detail:** MDC is thread-bound and **servlet threads are pooled/reused**, so we **clear the MDC in a `finally` block** — otherwise the next request on that thread inherits the old id.

**Extras:** honors an inbound `X-Request-Id` (so an id can span services) and echoes it on the response so a client can quote it in a bug report.

---

## PART 8 — TESTING (52 tests, all passing)

| Test class                         | Type             | Covers                                                                          |
| ---------------------------------- | ---------------- | ------------------------------------------------------------------------------- |
| `TransactionStateMachineTest` (22) | Unit             | Every legal/illegal state transition                                            |
| `TransferServiceTest` (7)          | Unit (Mockito)   | Idempotency dedupe, ledger nets to zero, insufficient balance, invalid transfer |
| `WebhookSignatureVerifierTest` (6) | Unit             | HMAC signature valid/invalid/tampered                                           |
| `ReconciliationServiceTest` (9)    | Unit (Mockito)   | All 4 mismatch types + clean run + gateway failure + in-flight skip             |
| `TransferFlowIntegrationTest` (3)  | Integration (H2) | Full transfer end-to-end                                                        |
| `WebhookFlowIntegrationTest` (4)   | Integration (H2) | Full top-up: order → webhook → balance update, duplicate webhook                |
| `SmokeTest` (1)                    | Integration      | Context loads                                                                   |

**Why H2 for integration tests?** Instant, zero-setup, isolated (create-drop = clean slate each run), can never touch real data.

**Why Mockito for unit tests?** Mock the repositories so there's no DB — fast, and lets us assert _exactly which_ repo calls happened (e.g. "a duplicate request did NOT write a second transaction").

**Unit vs integration difference (be ready):** Unit = one class in isolation, collaborators mocked, no Spring/DB. Integration = real Spring context + real (test) DB, verifies the pieces work together.

---

## PART 9 — BUGS & PROBLEMS WE ACTUALLY HIT (great "tell me about a challenge" material)

### Bug 1 — Razorpay 401 (account not activated)

**Symptom:** Every real Razorpay API call returned HTTP 401 "Authentication failed," even with correctly-formatted test keys.
**Diagnosis:** Direct API test (PowerShell Basic auth) proved the keys were rejected → root cause was the **Razorpay account wasn't activated (KYC pending)**, not bad keys.
**Fix / workaround:** Built a **dev-only mock top-up** (`/api/wallet/topup/mock`, gated by `@ConditionalOnProperty`) that reuses the **exact same ledger + state-machine logic** as the real webhook path — so the whole app (transfers, ledger, balances) demos end-to-end without Razorpay. Also drove the reconciliation gateway abstraction (simulated source).
**Interview lesson:** "I isolated whether it was my code or the account via a direct API probe, then designed a workaround that preserved the real engineering rather than faking it."

### Bug 2 — Normal user could see the admin dashboard

**Symptom:** After adding RBAC, `ms@gmail.com` (a normal user) got the admin dashboard.
**Diagnosis:** Not a security flaw — a **data** flaw. When Hibernate `ddl-auto=update` added the new `role` column to a table that **already had rows**, those legacy rows did **not** get the `@Builder.Default = USER` value (that only applies to newly-built Java objects, not existing DB rows). So old accounts ended up `ADMIN`, and their JWT legitimately carried `role=ADMIN`. The `hasRole` gate was working correctly — it was honoring bad data.
**Fix:** Corrected the rows (`UPDATE users SET role='USER'...`) **and** added a **DB-level `DEFAULT 'USER'`** (`columnDefinition`) so future column-adds/backfills are safe.
**Interview lesson:** Understand the difference between application-default and schema-default, and that `ddl-auto=update` never backfills existing rows sensibly — a reason real projects use **Flyway/Liquibase** migrations.

### Bug 3 — "Transfer not working" (non-bug)

**Symptom:** User reported transfers failing.
**Diagnosis:** Direct API test showed transfers worked; the user was sending to an **unregistered recipient email**, correctly rejected with 400.
**Lesson:** Reproduce before "fixing." It was correct validation, not a bug.

### Bug 4 — `replace_string` duplicated a field 6×

During editing, a near-identical replacement duplicated a field declaration repeatedly. Fix: matched the duplicated block explicitly. (Minor, tooling-related.)

### Recurring constraint — Razorpay account never activated

Everything gateway-dependent needed a workaround that still showed real engineering: mock top-up + gateway abstraction. This is a _feature_ of the story, not a weakness: "I built against an abstraction so the external dependency's availability never blocked the core work."

---

## PART 10 — ARCHITECTURE & LAYERING

**Layered architecture:**

- **Controller** — thin HTTP layer: validate body, delegate to service, wrap in `ResponseEntity`. No business logic.
- **Service** — all business logic, `@Transactional` boundaries.
- **Repository** — Spring Data JPA interfaces (we declare method names, Spring generates queries).
- **Entity** — JPA-mapped domain objects.
- **DTO** — purpose-built request/response shapes (records). Never expose entities directly.

**Why DTOs, not entities, on the wire?**

1. Security — a DTO has no `passwordHash` field, so it's _structurally impossible_ to leak it.
2. Decoupling — the API contract doesn't change when the DB schema does.
3. Avoids lazy-loading/serialization surprises.

**Why keep controllers thin?** Testability and single responsibility — business logic in services can be unit-tested without HTTP.

**`@Transactional` — why it matters:** Register creates a User _and_ a Wallet in two inserts. `@Transactional` makes both commit together or both roll back (atomicity — the "A" in ACID). Same principle guarantees ledger entries + balance update are all-or-nothing.

### Q: Why a monolith and not microservices?

**Short answer:** "For a single-team project of this size, a **modular monolith** gives me the speed, simplicity, and _strong consistency_ a payments system needs — without the operational overhead of microservices. Microservices solve _organizational_ and _scale_ problems I don't have yet, and they'd actively make the money-critical parts harder."

**Why monolith was the right call:**

1. **Money needs strong consistency → one DB, one transaction.** Transfer/top-up rely on a single `@Transactional` boundary (debit + credit + balance + ledger commit or roll back together). Split across services with separate DBs, I'd lose local ACID and need **distributed transactions / Sagas / eventual consistency** with compensating actions — much riskier for a wallet balance.
2. **No team/scale pressure.** Microservices pay off when many teams deploy independently or one component scales very differently. One codebase, one dev → independent deployability buys nothing, costs a lot.
3. **Lower operational overhead.** No service discovery, API gateway, inter-service auth, distributed tracing, multiple pipelines/DBs to run.
4. **Simpler debug & test.** One process = one stack trace; my 52 tests run in-process against H2 without spinning up multiple services + a broker.

**The nuance that scores points — it's a _modular_ monolith, not a big ball of mud.** Code is split by domain package (`auth · wallet/transfer · topup · webhook · reconciliation · admin`), each with clean controller→service→repository layering. Clean boundaries mean I can **extract-when-needed** later with minimal rework.

**"When _would_ you split it?"** Name concrete triggers, not "when it's big": the **webhook receiver** (spiky external traffic), **reconciliation** (heavy scheduled batch, different resource profile), **notifications** (naturally async → queue). But keep the **core ledger/transfer in one service** to preserve the ACID transaction — that's the part you don't want to distribute.

**One-liner:** "Monolith for correctness and speed; modular so I can extract later. Microservices would trade my strong-consistency guarantee for distributed-transaction complexity I don't need at this scale."

---

## PART 11 — FRONTEND

- **React 18 + Vite + React Router.** `AuthContext` holds the logged-in user; `ProtectedRoute` guards routes (and `requireAdmin` for admin routes).
- **Axios interceptors** (`api.js`): a **request** interceptor attaches the access token; a **response** interceptor catches 401, does **one silent refresh** (shared in-flight promise so concurrent 401s don't each refresh), retries the original request, and on refresh failure forces logout. This is what keeps the user logged in across 15-min access-token expiries.
- **Admin dashboard**: metric cards + **Recharts** (pie = transactions by status, bar = by type) + reconciliation control panel (run button + logs table) + global transaction feed.
- Decoding the JWT on the frontend is safe because it's only used to display identity/route — never for a security decision (the server re-verifies every request).

---

## PART 12 — NON-FUNCTIONAL / OPS

- **CI:** GitHub Actions runs backend tests (H2) + frontend build on every push/PR to `main`.
- **Config via env vars:** `${ENV_VAR:default}` pattern everywhere; `.env` gitignored, `.env.example` committed.
- **Scheduled job:** `@EnableScheduling` + `@Scheduled(cron=...)` runs reconciliation daily at 02:00 IST (reconciles yesterday).
- **`@ConditionalOnProperty`:** feature flags (mock top-up, gateway provider) create beans only when enabled — clean way to keep dev-only features out of prod.

---

## PART 13 — TRADE-OFFS I CONSCIOUSLY MADE (interviewers love these)

1. **`ddl-auto=update` instead of Flyway/Liquibase** — convenient while entities evolve, but can't safely rename/drop columns and doesn't version the schema. A real prod app would use migrations. (I even _hit_ the downside — Bug 2.)
2. **Cached balance + ledger (hybrid)** instead of pure event sourcing — pragmatic; fast reads, still auditable.
3. **Simulated gateway** instead of blocking on Razorpay KYC — keeps the core engineering demonstrable.
4. **Authenticated-only admin** rather than a full role hierarchy — one `ADMIN` role is enough to demo RBAC without over-engineering.
5. **No Redis/rate-limiting** — spec says "if time allows"; DB unique constraints already cover idempotency durably.
6. **Synchronous MVC, not reactive** — simpler, and load doesn't justify WebFlux.

---

## PART 14 — RAPID-FIRE Q&A (self-test)

**Q: What happens end-to-end when a user adds money?**
A: `topup/initiate` (with Idempotency-Key) creates a Razorpay order + a local transaction in `CREATED`. Browser opens Razorpay Checkout and pays. Razorpay sends a signed `payment.captured` **webhook**; we verify the signature, move the transaction `CREATED→PENDING→SUCCESS` via the state machine, write balanced ledger entries (CREDIT the wallet), update the cached balance — all in one `@Transactional`. The browser redirect is _not_ trusted for this.

**Q: What happens on a transfer?**
A: `transfer` (with Idempotency-Key) → dedupe check → validate (recipient exists, not self, positive amount) → balance check → within one transaction: DEBIT sender, CREDIT receiver (nets to zero), update both cached balances, mark SUCCESS.

**Q: How do you prevent double-spend on a double-click?**
A: Idempotency-Key dedupe (app check + DB unique constraint), and the whole transfer is atomic via `@Transactional`.

**Q: How do you know your balances are correct?**
A: They're derived from a double-entry ledger that always nets to zero, updated atomically with the cached balance, and cross-checked daily by reconciliation.

**Q: What if a webhook never arrives?**
A: The transaction stays `PENDING`; reconciliation detects the gateway shows a captured payment we didn't finalize (`MISSING_LOCALLY`/`STATUS_MISMATCH`) and flags it for review.

**Q: How is a webhook secured?**
A: HMAC-SHA256 signature over the raw body with the shared webhook secret; we recompute and compare before trusting. Duplicates are ignored via unique `razorpay_event_id`.

**Q: Why access + refresh tokens?**
A: Short access token limits exposure if stolen; refresh token (revocable, rotated) lets users stay logged in without re-entering credentials, and can be invalidated on logout/theft.

**Q: Biggest challenge?**
A: The Razorpay account never activated (KYC), which would have blocked half the app. I diagnosed it via a direct API probe, then built a mock top-up (reusing the real ledger/state-machine logic) and a gateway abstraction for reconciliation, so the external dependency never blocked demonstrating the core engineering.

**Q: What would you add with more time?**
A: Flyway migrations, rate-limiting on login/transfer, the withdrawal flow, Testcontainers (real MySQL in tests), pagination/filters on the admin feed, and completing real Razorpay once activated.

**Q: How does reconciliation actually match records?**
A: Join on gateway payment id; build local-topups map and gateway-payments map for the day; walk both directions to find the 4 mismatch types; log results read-only. It detects, never auto-fixes.

**Q: Why is the ledger "double-entry"?**
A: Every movement has two equal-and-opposite entries (debit + credit) that sum to zero, mirroring real accounting — this makes every balance fully traceable and any imbalance a detectable bug.

---

## PART 14.5 — FUTURE WORK / SECURITY ROADMAP

### Q: Would you add email OTP so people can't sign up with fake emails?

**Frame it precisely first:** "That's email _verification_ (prove inbox ownership at registration), not real 2FA (at login) and not identity/KYC. Anyone can create a real Gmail in a minute, so it stops typos and casual spam signups — it doesn't verify a real person. For actual second-factor auth I'd use **TOTP (authenticator app)**, not email, because if the inbox is compromised so is an email OTP."

**The design I'd use (verify once at registration, then gate money movement):**

```
Register → User status = UNVERIFIED (wallet still 0)
         → generate 6-digit OTP, store HASH + expiry (10 min), email it
Verify   → POST /auth/verify { email, otp }
         → compare hash, check expiry + attempt count → status = VERIFIED
Gate     → block top-up / transfer until VERIFIED (login may be allowed)
```

**The non-obvious details interviewers probe (get these right):**

- **Hash the OTP** in the DB like a password — never plaintext.
- **Short expiry** (5–10 min) **+ max attempts** (≈5) → a 6-digit code can't be brute-forced.
- **Rate-limit the send endpoint** → prevents email-bombing a victim.
- **Constant-time compare** of the code.
- **Don't leak existence** — "if the email exists, we've sent a code" (identical response either way).
- **Resend invalidates the old code** (idempotent issue).

**Why I haven't built it yet (scoping judgment):** it needs an email provider + deliverability handling, and my stronger engineering stories are already done (idempotency, double-entry ledger, reconciliation, signed webhooks). If I add it, I'd do **registration verification with a dev-mode "log OTP to console"** so it stays a clean backend exercise instead of fighting email deliverability. Notice it reuses the _exact same_ security thinking as my webhook-signature + idempotency work: hash secrets, expire them, cap attempts, rate-limit, constant-time compare.

**One-liner:** "I scoped email OTP as registration verification, not login 2FA — it proves inbox ownership, not identity. I'd hash the code, expire it in 10 min, cap attempts, rate-limit sends, and gate money movement behind a VERIFIED status. For real 2FA I'd use TOTP, not email."

---

## PART 15 — ONE-LINE DEFINITIONS TO MEMORIZE

- **Idempotency:** same request twice = same single effect.
- **Double-entry ledger:** every transaction = balanced debit + credit that net to zero; balance is derived.
- **State machine:** transactions only move through allowed states; can't skip verification.
- **Webhook trust:** confirm payments via signed server-to-server webhook, not the browser.
- **Reconciliation:** daily read-only compare of our records vs the gateway to flag drift.
- **JWT:** signed, stateless access token; revocable refresh token in DB with rotation.
- **BCrypt:** slow, salted password hash.
- **MDC correlation id:** per-request id on every log line for tracing.
- **DTO:** wire shape separate from entity; can't leak sensitive fields.
- **`@Transactional`:** all-or-nothing atomicity across multiple DB writes.

---

_Prepared as a personal interview-prep reference for the LedgerPay project. If you can explain each section out loud without reading it, you're ready._
