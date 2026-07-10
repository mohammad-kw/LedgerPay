# LedgerPay — Architecture & Flow Diagrams

> **Purpose:** Visual, whiteboard-ready diagrams for "draw the architecture" / "walk me through the flow" interview questions. All ASCII so they render anywhere. Practice re-drawing these on paper.

---

## 1. System Overview (the big picture)

```
        ┌─────────────┐         HTTPS / JSON          ┌──────────────────────────┐
        │   Browser   │  ─────────────────────────▶   │   Spring Boot Backend    │
        │  (React +   │   Authorization: Bearer JWT   │      (com.wallet)        │
        │   Vite)     │  ◀─────────────────────────   │                          │
        └─────────────┘                               │  Controller → Service    │
              │                                        │      → Repository        │
              │ opens Razorpay Checkout                └───────────┬──────────────┘
              ▼                                                    │ JPA / Hibernate
        ┌─────────────┐      payment.captured (webhook)           ▼
        │  Razorpay   │  ───────────────────────────▶       ┌───────────┐
        │ (test mode) │      X-Razorpay-Signature            │   MySQL   │
        └─────────────┘                                      └───────────┘
              ▲                                                    ▲
              │ daily reconciliation reads gateway records        │
              └────────────────────────────────────────────────  ┘
                        (ReconciliationService)
```

**One-liner:** React talks to Spring Boot over JWT-secured REST; Spring Boot persists to MySQL via JPA; Razorpay confirms payments via signed webhooks; a daily job reconciles our DB against Razorpay.

---

## 2. Backend Layered Architecture

```
   HTTP request
        │
        ▼
┌───────────────────┐   thin: validate body, read JWT principal, wrap ResponseEntity
│   Controller      │   e.g. WalletController, AuthController, AdminController
└─────────┬─────────┘
          │ calls
          ▼
┌───────────────────┐   ALL business logic + @Transactional boundaries
│    Service        │   e.g. TransferService, TopUpService, WebhookService,
└─────────┬─────────┘        ReconciliationService, AdminService
          │ uses
          ▼
┌───────────────────┐   Spring Data JPA interfaces (we declare, Spring implements)
│   Repository      │   e.g. TransactionRepository, WalletRepository
└─────────┬─────────┘
          │ maps
          ▼
┌───────────────────┐   JPA @Entity (DB rows)      DTO (records) = wire shapes
│   Entity  /  DTO  │   User, Wallet, Transaction, LedgerEntry ...
└───────────────────┘

  Cross-cutting (wrap every request):
   • CorrelationIdFilter  → per-request id in MDC (runs first)
   • JwtAuthenticationFilter → authenticate the Bearer token
   • GlobalExceptionHandler  → exceptions → clean JSON + status codes
```

**Why layered:** each layer has one job; business logic (services) is unit-testable without HTTP; DTOs keep the API contract separate from the DB schema and can't leak `passwordHash`.

---

## 3. Registration Flow

```
Browser                Controller           AuthService              DB
  │  POST /register        │                     │                    │
  │───────────────────────▶│                     │                    │
  │                        │  register(req)       │                    │
  │                        │────────────────────▶ │                    │
  │                        │                      │ existsByEmail? ────▶│
  │                        │                      │◀─── false          │
  │                        │        ┌─────────── @Transactional ──────┐│
  │                        │        │ save User (BCrypt hash) ───────▶ ││
  │                        │        │ save Wallet (balance 0) ───────▶ ││
  │                        │        └── both commit together ─────────┘│
  │   201 {id,name,email}  │◀──────────────────── │                    │
  │◀───────────────────────│                     │                    │
```

**Key point:** User + Wallet in one transaction → atomic → no wallet-less users.

---

## 4. Login + Authenticated Request Flow

```
(A) LOGIN
Browser           AuthService          AuthManager        DB
  │ POST /login       │                    │               │
  │──────────────────▶│ authenticate ─────▶│ load user +   │
  │                   │                    │ BCrypt check ─▶│
  │                   │◀── ok (or 401) ────│               │
  │                   │ generateAccessToken (JWT, 15m)      │
  │                   │ + refresh token (UUID) saved ──────▶│
  │ {access,refresh}  │                    │               │
  │◀──────────────────│                    │               │

(B) SUBSEQUENT REQUEST
Browser                 JwtAuthenticationFilter     SecurityConfig    Controller
  │ GET /wallet/balance      │                          │                │
  │ Bearer <access> ────────▶│ verify signature+expiry  │                │
  │                          │ load user, set principal │                │
  │                          │────────────────────────▶ │ rule: authed?  │
  │                          │                          │──── yes ──────▶│
  │        200 balance       │◀─────────────────────────────────────────│
```

**Key point:** access token verified by **signature only** (stateless, no DB hit for auth); the filter populates the SecurityContext, then SecurityConfig's rules decide access.

---

## 5. Top-up Flow (the two-step Razorpay pattern) ⭐

```
STEP 1 — INITIATE (browser-driven)
Browser              TopUpService          Razorpay        DB
  │ POST /topup/initiate    │                 │             │
  │ Idempotency-Key ───────▶│ dedupe check ──────────────▶ │
  │                         │ create order ──▶│             │
  │                         │◀── order_id ────│             │
  │                         │ save txn CREATED ───────────▶ │
  │  {order_id, key_id}     │                 │             │
  │◀────────────────────────│                 │             │
  │                                                         │
  │ opens Razorpay Checkout, user pays (test card/UPI)      │
  │────────────────────────────────────────▶│              │
  │                                          │              │
  │ ◀╌╌ browser redirect "success" ╌╌ (NOT TRUSTED!) ╌╌╌╌╌ │
  │                                          │              │
STEP 2 — CONFIRM (server-to-server, the source of truth)
  │                                          │              │
  │              Razorpay ──POST /webhooks/razorpay──▶ WebhookService
  │              X-Razorpay-Signature                     │
  │                                                       │ 1. verify HMAC signature
  │                                                       │ 2. dup check (event_id)
  │                                                       │ 3. txn CREATED→SUCCESS (state machine)
  │                                                       │ 4. CREDIT ledger entry
  │                                                       │ 5. update wallet balance
  │                                                       │    ── all @Transactional ──
```

**Key point (say this):** the browser "success" redirect is **never trusted** to credit money. Only the **signature-verified webhook** does — that's the whole security model of the payment flow.

---

## 6. Transfer Flow (double-entry, atomic) ⭐

```
POST /transfer  (Idempotency-Key header)
        │
        ▼
  ┌── dedupe: key already used? ── yes ─▶ return original result (no double-spend)
  │        │ no
  │        ▼
  │  validate: sender wallet exists? receiver exists? not self?
  │        │
  │        ▼
  │  balance check: sender.balance.compareTo(amount) >= 0 ?  ── no ─▶ 422 Insufficient
  │        │ yes
  │        ▼
  │  ┌──────────────── @Transactional (all-or-nothing) ─────────────────┐
  │  │  save txn CREATED                                                 │
  │  │  DEBIT  amount on sender    (balance_after = bal - amount)  ┐     │
  │  │  CREDIT amount on receiver  (balance_after = bal + amount)  ├ net 0│
  │  │  update sender.balance, receiver.balance                   ┘     │
  │  │  state machine: CREATED → SUCCESS                                 │
  │  └──────────────────────────────────────────────────────────────────┘
  │        │
  └────────┴─▶ 200 { transaction, newBalance }
```

**Ledger invariant:**

```
   DEBIT(sender) + CREDIT(receiver) = -200 + 200 = 0   ✅ money conserved
```

**Key point:** everything from "save txn" to "SUCCESS" is one transaction → the sender is never debited without the receiver being credited. `compareTo` (not `equals`) for the BigDecimal balance check.

---

## 7. Transaction State Machine

```
        ┌──────────┐
        │ CREATED  │
        └────┬─────┘
       ┌─────┼───────────────┐
       ▼     ▼               ▼
  ┌────────┐ │          ┌─────────┐
  │PENDING │ │          │ SUCCESS │────────▶ ┌──────────┐
  └───┬────┘ │          └─────────┘  refund  │ REVERSED │ (terminal)
      │      │               ▲               └──────────┘
      ├──────┴───────────────┘ (via verified webhook)
      ▼
  ┌────────┐
  │ FAILED │ (terminal)
  └────────┘

  ILLEGAL (rejected):  FAILED→anything,  REVERSED→anything,  SUCCESS→SUCCESS
```

**Key point:** `CREATED→SUCCESS` is allowed but only fires _after_ a signature-verified webhook, so verification is never skipped. FAILED/REVERSED are terminal — enforced by `assertCanTransition`.

---

## 8. Reconciliation Flow ⭐ (the differentiator)

```
Daily @Scheduled (02:00) OR  POST /admin/reconciliation/run
        │
        ▼
  reconcileForDate(yesterday)
        │
        ├─▶ LOCAL  side:  our TOPUP txns for the day, keyed by payment_id
        │
        └─▶ GATEWAY side:  PaymentGatewayReconciliationSource.fetchPaymentsForDate()
                           (interface → Simulated OR Razorpay impl)

   FULL OUTER JOIN on payment_id (walk BOTH directions):

   gateway ─▶ local:                          local ─▶ gateway:
   ┌───────────────────────────┐              ┌────────────────────────────┐
   │ no local match            │              │ local SUCCESS, not returned │
   │   → MISSING_LOCALLY        │              │   → MISSING_AT_GATEWAY      │
   │ status differs            │              └────────────────────────────┘
   │   → STATUS_MISMATCH        │
   │ amount differs            │
   │   → AMOUNT_MISMATCH        │
   └───────────────────────────┘
        │
        ▼
  save ReconciliationLog { total_checked, mismatches_found, details_json, status }
  (READ-ONLY: detects drift, never auto-fixes balances)
```

**Gateway abstraction (one config line swaps the source):**

```
   ReconciliationService ──depends on──▶ PaymentGatewayReconciliationSource (interface)
                                              ▲                    ▲
                              app.reconciliation.gateway-provider  │
                                   = "simulated"          = "razorpay"
                              SimulatedGatewaySource   RazorpayGatewaySource
```

**Key point:** two-directional walk catches drift on either side; only TOPUPs (only they hit the gateway); read-only; engine depends only on the interface → testable without Razorpay, swap via config.

---

## 9. Idempotency (why a double-click is safe)

```
   Request 1 (key=abc) ─▶ findByIdempotencyKey("abc") → empty → process → save txn(key=abc)
   Request 2 (key=abc) ─▶ findByIdempotencyKey("abc") → FOUND → return original result
                                                                  (no second txn)

   Race (both at once): DB UNIQUE(idempotency_key) → 2nd insert fails → treated as "already done"
```

**Two layers of defense:** app-level check + DB `UNIQUE` constraint (closes the check-then-insert race).

---

## 10. Security Layers (defense in depth)

```
   Request
     │
     ▼  1. CorrelationIdFilter      → tag logs
     ▼  2. JwtAuthenticationFilter  → verify Bearer JWT signature → set principal
     ▼  3. SecurityConfig rules:
             /api/auth/**      → permitAll
             /api/webhooks/**  → permitAll (secured by HMAC instead)
             /api/admin/**     → hasRole("ADMIN")   (401 anon / 403 non-admin)
             anyRequest        → authenticated
     ▼  4. @Valid on body           → 400 on bad input
     ▼  5. Service checks ownership  → IDOR-safe (id never trusted from client)
     ▼  Controller runs
```

**Layers:** correlation → authentication → authorization → input validation → ownership. Plus BCrypt at rest, secrets in env vars, stateless sessions, CSRF off (bearer not cookies).

---

## 11. Token Lifecycle

```
  login ──▶ access token (JWT, 15 min, stateless)  +  refresh token (UUID, 7 days, in DB)
              │                                            │
   every request uses access token                        │
              │                                            │
   access expires → 401 ──▶ Axios interceptor ──▶ POST /refresh (refresh token)
                                                     │
                                    rotation: old refresh REVOKED, new pair issued
                                                     │
                                          retry original request with new access
              │
   logout / theft ──▶ refresh token revoked in DB (access simply expires ≤15 min)
```

**Key point:** access = fast & stateless (signature); refresh = revocable (DB) + rotated (one-time use).

---

## Data Model (entity relationships)

```
   User 1───1 Wallet 1───* LedgerEntry *───1 Transaction
    │                                            │
    │ (role: USER/ADMIN)          Transaction *──┤ senderWallet   (null for TOPUP)
    │                             Transaction *──┘ receiverWallet  (null for WITHDRAWAL)
    │
   RefreshToken *───1 User

   WebhookEvent        (standalone audit: razorpay_event_id unique)
   ReconciliationLog   (standalone audit: one row per run)
```

---

_Pair with: `INTERVIEW_PREP.md` (concepts/trade-offs), `CODE_WALKTHROUGH.md` (code snippets), `INTERVIEW_CHEATSHEET.md` (1-page recall)._
