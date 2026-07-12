# LedgerPay — Complete Onboarding Guide (from zero)

> **Who this is for:** someone who has **never seen this project** and wants to understand it _completely_ — what it is, why each file exists, and how a request flows from a button click all the way to the database and back. Read it top to bottom. You don't need prior knowledge of the project; basic Java + JavaScript familiarity is enough.

**Reading time:** ~45–60 minutes. Take it in the 10 parts below.

---

## Part 0 — What is LedgerPay, in plain English?

LedgerPay is a **digital wallet** — think of a mini Paytm / Venmo. A user can:

1. **Register** and log in.
2. **Add money** to their wallet.
3. **Send money** to another user.
4. See their **transaction history**.
5. An **admin** can see system-wide stats, all users, and run "reconciliation".

But the _point_ of the project isn't the features — plenty of apps do this. The point is doing it **correctly**, the way a real fintech company must:

- Money must **never be created or lost** by a bug or a double-click.
- Every rupee must be **traceable**.
- Payments must be confirmed **securely**, not by trusting the browser.

Those guarantees are what make this a "serious" project. Keep them in mind — every design choice serves them.

---

## Part 1 — The 30,000-foot view (how the pieces fit)

There are **three separate running programs**:

```
   ┌────────────┐     HTTP/JSON      ┌────────────┐     SQL      ┌──────────┐
   │  FRONTEND  │ ─────────────────▶ │  BACKEND   │ ───────────▶ │ DATABASE │
   │ React app  │ ◀───────────────── │ Spring Boot│ ◀─────────── │  MySQL   │
   │ (browser)  │                     │  (server)  │              └──────────┘
   └────────────┘                     └────────────┘
```

- **Frontend** (folder `frontend/`): the website you see. Built with **React**. Runs in the browser. It has no secrets and no money logic — it just shows screens and calls the backend.
- **Backend** (folder `backend/`): the brain. Built with **Spring Boot** (Java). It holds _all_ the business rules, security, and money logic. Everything important happens here.
- **Database** (MySQL): permanent storage — users, wallets, transactions, etc.

**Golden rule to remember:** the frontend is "dumb" (just UI), the backend is the "source of truth". Never trust the frontend for anything important.

---

## Part 2 — Where to start & how to run it

### 2.1 Read these first (in order)

1. `README.md` — the project overview + live demo link.
2. `PROJECT_SPEC.md` — the original requirements the project was built against.
3. `ARCHITECTURE_DIAGRAMS.md` — visual flows (great companion to this guide).
4. This file.

### 2.2 Run it locally

**You need:** Java 21, Maven, MySQL 8, Node 20 + pnpm.

```bash
# 1. Create the database (once)
#    In MySQL: CREATE DATABASE ledgerpay;

# 2. Backend (from backend/ folder)
mvn spring-boot:run          # starts on http://localhost:8080

# 3. Frontend (from frontend/ folder, in a second terminal)
corepack pnpm install
corepack pnpm dev            # starts on http://localhost:5173
```

Open `http://localhost:5173`, register a user, and you're in. (Secrets like DB password come from `backend/.env` — copy `backend/.env.example` to `backend/.env` and fill it in.)

---

## Part 3 — The backend, layer by layer (the mental model)

Every backend request flows through the **same 4 layers**, top to bottom. Understanding this one picture unlocks the whole codebase:

```
   HTTP request (e.g. POST /api/wallet/transfer)
        │
        ▼
   1. CONTROLLER   ── "the receptionist": reads the request, calls a service,
        │              returns a response. NO business logic.
        ▼
   2. SERVICE      ── "the brain": all rules, calculations, @Transactional
        │              money operations live here.
        ▼
   3. REPOSITORY   ── "the librarian": reads/writes the database. We only
        │              declare method names; Spring writes the SQL.
        ▼
   4. ENTITY       ── "the shape of a table row" (User, Wallet, Transaction).
   (+ DTO = the shape of the JSON we send/receive over the web)
```

**Why split it this way?** Each layer has ONE job, so the code is testable and changes stay contained. The business rules (services) can be tested without a web server or a real database.

### The package map (folder = responsibility)

Inside `backend/src/main/java/com/wallet/`:

| Package           | What lives here                               | Analogy                   |
| ----------------- | --------------------------------------------- | ------------------------- |
| `controller/`     | HTTP endpoints (`@RestController`)            | Reception desk            |
| `service/`        | Business logic + transactions                 | The brain                 |
| `repository/`     | Database access (Spring Data JPA)             | The librarian             |
| `entity/`         | Database table shapes (`@Entity`)             | Table blueprints          |
| `dto/`            | Request/response JSON shapes (`record`)       | Envelopes                 |
| `security/`       | JWT, passwords, who-can-do-what               | Security guard            |
| `webhook/`        | Handles Razorpay's payment callbacks          | Payment confirmation desk |
| `reconciliation/` | Daily "do our records match the gateway?" job | The auditor               |
| `exception/`      | Custom errors + one global error handler      | Complaints department     |
| `logging/`        | Correlation-id tagging on every log line      | The stenographer          |
| `config/`         | Startup setup (admin seed, Razorpay client)   | Facilities setup          |

> 💡 **Tip for your friend:** almost every `.java` file in this project has a big comment at the top explaining _why_ it exists. Open any file and read that comment first — the code was written to be learned from.

---

## Part 4 — The data model (the 7 tables)

Before code, understand the **nouns**. These are the entities in `entity/`:

```
   User ───owns──▶ Wallet ───has many──▶ LedgerEntry ◀──belongs to── Transaction
    │  (1:1)                (1:many)                      (each txn has
    │                                                      2 ledger entries)
    └──has many──▶ RefreshToken

   WebhookEvent        (standalone: a log of every payment callback received)
   ReconciliationLog   (standalone: one row per audit run)
```

| Entity file              | Represents                   | Key fields                                                 |
| ------------------------ | ---------------------------- | ---------------------------------------------------------- |
| `User.java`              | A person's account           | name, email, `passwordHash` (BCrypt!), `role` (USER/ADMIN) |
| `Wallet.java`            | One user's money             | `balance` (a `BigDecimal`, never a `double`)               |
| `Transaction.java`       | One money event              | type (TOPUP/TRANSFER), status, amount, `idempotencyKey`    |
| `LedgerEntry.java`       | One side of a money movement | DEBIT or CREDIT, `balanceAfter`                            |
| `RefreshToken.java`      | A long-lived login token     | token value, expiry, revoked flag                          |
| `WebhookEvent.java`      | A received Razorpay callback | unique event id (prevents duplicates)                      |
| `ReconciliationLog.java` | Result of one audit run      | counts, mismatches, details JSON                           |

**Two things to internalize here:**

1. **Money is always `BigDecimal`**, never `double`. Floating-point can't represent `0.10` exactly — unacceptable for money.
2. **Balance is stored twice**: once as a cached number on `Wallet`, and once as the sum of `LedgerEntry` rows. The ledger is the "truth"; the cached balance is for speed. Reconciliation and atomic writes keep them in sync. (More below.)

---

## Part 5 — The FIVE core concepts (the heart of the project)

If your friend learns nothing else, they should learn these five. Everything else is plumbing.

### 5.1 Idempotency — "a double-click can't charge you twice"

Every money-moving request carries an **`Idempotency-Key`** header (a unique id the frontend generates). Before doing anything, the backend checks: _"have I already processed this key?"_

- If **yes** → it returns the original result and does nothing new.
- If **no** → it processes and saves the key.

Defended **twice**: an app-level check _and_ a database `UNIQUE` constraint on the key (in case two requests race at the exact same millisecond).

> Files: `TransferService.java`, `TopUpService.java`, `TransactionRepository.findByIdempotencyKey`.

### 5.2 Double-entry ledger — "every rupee is traceable"

Borrowed from real accounting. Every money movement writes **two** rows that cancel out:

```
   Transfer ₹200 from Asha to Ravi:
     LedgerEntry 1:  DEBIT  ₹200  on Asha's wallet   (-200)
     LedgerEntry 2:  CREDIT ₹200  on Ravi's wallet   (+200)
                                                     ─────
                                          net effect:   0   ✅ money conserved
```

If the two sides ever don't sum to zero, that's a **detectable bug**. The wallet's `balance` field is just a fast cache of "sum of my ledger entries".

> Files: `TransferService.java`, `LedgerEntry.java`, `EntryType.java`.

### 5.3 Transaction state machine — "you can't skip steps"

A transaction moves through states: `CREATED → PENDING → SUCCESS`, or `→ FAILED`, or `SUCCESS → REVERSED`. Illegal jumps (like `FAILED → SUCCESS`) are **rejected in code**. This prevents, e.g., marking a payment successful without it actually being verified.

> Files: `TransactionStateMachine.java`, `TransactionStatus.java`, `IllegalStateTransitionException.java`.

### 5.4 Webhooks — "trust the bank, not the browser"

When a user pays via Razorpay, the browser might _say_ "payment succeeded" — but a hacker could fake that. So we **never credit the wallet based on the browser**. Instead, Razorpay's _servers_ send our _server_ a **webhook** (a direct server-to-server message), cryptographically **signed** with a shared secret. We recompute the signature; only if it matches do we credit the wallet.

> Files: `webhook/WebhookController.java`, `webhook/WebhookService.java`, `webhook/WebhookSignatureVerifier.java`.

### 5.5 Reconciliation — "trust, but verify, daily"

Once a day, a scheduled job compares **our** top-up records against **the gateway's** records and flags any mismatches (missing on either side, wrong status, wrong amount). It's **read-only** — it _detects_ problems, it never silently "fixes" money. This is the project's standout feature.

> Files: `reconciliation/` (the whole folder), especially `ReconciliationService.java`.

---

## Part 6 — Follow ONE request end-to-end (the "aha" moment)

Let's trace **"Asha sends ₹200 to Ravi"** through every file. This single walkthrough ties everything together.

```
1. FRONTEND
   Asha clicks "Send" in SendMoneyModal.jsx
   → walletService.js calls: POST /api/wallet/transfer
        headers: { Authorization: "Bearer <JWT>", Idempotency-Key: "<uuid>" }
        body:    { receiverEmail: "ravi@…", amount: 200 }
        (api.js automatically attaches the JWT via an interceptor)

2. BACKEND — request enters the filter chain
   a. CorrelationIdFilter.java  → tags this request with a unique log id
   b. JwtAuthenticationFilter.java → reads the JWT, verifies its signature,
        and establishes "this request is Asha" (no DB session needed)
   c. SecurityConfig.java rules → "/api/wallet/** needs to be authenticated" ✅

3. CONTROLLER
   WalletController.transfer(...) receives the validated request body
   (@Valid checks amount ≥ 1, etc. via TransferRequest.java's annotations)
   → calls transferService.transfer(ashaId, request, idempotencyKey)

4. SERVICE (the important part) — TransferService.java, all @Transactional:
   • Idempotency check: seen this key before? If yes, return old result.
   • Load Asha's wallet and Ravi's wallet.
   • Rule checks: Ravi exists? Not sending to self? Enough balance?
        (balance compared with BigDecimal.compareTo, not ==)
   • Create a Transaction (status CREATED).
   • Write TWO LedgerEntry rows: DEBIT Asha 200, CREDIT Ravi 200.
   • Update both wallet balances.
   • Move the transaction CREATED → SUCCESS via TransactionStateMachine.
   • Because it's all ONE @Transactional method: if ANY step fails,
     EVERYTHING rolls back. Asha is never debited without Ravi being credited.

5. REPOSITORY
   TransactionRepository / WalletRepository / LedgerEntryRepository
   save the rows → Spring Data JPA turns these into SQL INSERT/UPDATE.

6. DATABASE
   MySQL commits the transaction. Done.

7. RESPONSE travels back up:
   Service → Controller wraps it in a DTO (TransferResponse.java) →
   JSON → frontend → Dashboard.jsx refreshes and shows the new balance.
```

Read `TransferService.java` alongside this list — it will click.

---

## Part 7 — The security system (how login works)

### 7.1 Registering & passwords

When you register, `AuthService.java` hashes your password with **BCrypt** (a deliberately slow, salted, one-way hash) and stores only the hash. The real password is never saved and cannot be recovered.

> Files: `AuthService.java`, `SecurityConfig.passwordEncoder()`.

### 7.2 Logging in & the two tokens

On login you get **two** tokens:

- **Access token (JWT):** short-lived (15 min), sent with every request. It's _stateless_ — the server verifies it by checking its signature, no database lookup. Fast.
- **Refresh token:** long-lived (7 days), stored in the DB. When the access token expires, the frontend silently uses the refresh token to get a new one. Being in the DB means it can be **revoked** (logout, theft).

> Files: `JwtService.java` (creates/verifies JWTs), `RefreshToken.java`, `AuthService.java`. On the frontend, `api.js` has an **interceptor** that auto-refreshes on a 401.

### 7.3 Every request after login

`JwtAuthenticationFilter.java` runs on every request, reads the `Authorization: Bearer <token>` header, verifies it, and tells Spring Security who you are. Then `SecurityConfig.java` decides if you're allowed:

- `/api/auth/**` → public (you're not logged in yet)
- `/api/webhooks/**` → public (secured by signature instead)
- `/api/admin/**` → must have the ADMIN role
- everything else → must be logged in

### 7.4 Roles (RBAC)

A user has a `role`: `USER` or `ADMIN`. There is **no "sign up as admin"** button (that would be a security hole). The first admin is created at startup by `AdminSeeder.java` from environment variables.

---

## Part 8 — The frontend, file by file

Inside `frontend/src/`:

| File / folder                     | Job                                                                                                   |
| --------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `main.jsx`                        | Entry point — mounts the React app into the page                                                      |
| `App.jsx`                         | The **route table** — which URL shows which page                                                      |
| `context/AuthContext.jsx`         | Global "who is logged in" state; decodes the JWT for name/role                                        |
| `services/api.js`                 | The **axios instance** — attaches the JWT, auto-refreshes on 401. **The single door to the backend.** |
| `services/authService.js`         | login/register/refresh API calls                                                                      |
| `services/walletService.js`       | balance/transfer/top-up API calls                                                                     |
| `services/adminService.js`        | admin API calls (metrics, users, reconciliation)                                                      |
| `components/ProtectedRoute.jsx`   | Wraps pages that need login (and `requireAdmin` for admin pages)                                      |
| `components/AddMoneyModal.jsx`    | "Add money" popup (real Razorpay flow + demo mock mode)                                               |
| `components/SendMoneyModal.jsx`   | "Send money" popup                                                                                    |
| `pages/Login.jsx`, `Register.jsx` | Auth screens                                                                                          |
| `pages/Dashboard.jsx`             | The main user screen — balance + transactions                                                         |
| `pages/AdminDashboard.jsx`        | Admin metrics + charts + reconciliation panel                                                         |
| `pages/AdminUsers.jsx`            | Admin: list of all users                                                                              |
| `pages/AdminUserDetail.jsx`       | Admin: one user + their transaction history                                                           |
| `utils/format.js`                 | Formats money (₹1,234.50) and dates                                                                   |

**How a frontend screen talks to the backend:** a page (e.g. `Dashboard.jsx`) calls a function in a `services/*.js` file, which calls `api.js`, which sends the HTTP request with the JWT attached. Responses come back as plain JSON. The frontend never does money math — it just displays what the backend returns.

---

## Part 9 — The supporting cast (config, logging, errors)

- **`config/AdminSeeder.java`** — runs once at startup; creates the admin account from env vars.
- **`config/RazorpayConfig.java` / `RazorpayProperties.java`** — sets up the Razorpay client from config.
- **`logging/CorrelationIdFilter.java`** — gives every request a unique id that appears on every log line for that request, so you can trace one request's whole journey in the logs.
- **`exception/GlobalExceptionHandler.java`** — one place that catches all errors and turns them into clean JSON (`{ status, message, ... }`) with the right HTTP code (400, 404, 409, etc.). Each custom exception (e.g. `InsufficientBalanceException`) maps to a specific status.
- **`dto/` records** — every request/response has a dedicated shape. Crucially, DTOs **omit** sensitive fields (like `passwordHash`), so secrets _can't_ leak — the shape simply has no place to put them.

---

## Part 10 — How it's deployed & tested

- **Tests:** `backend/src/test/` — 52 tests (JUnit 5 + Mockito) covering transfers, ledger balancing, idempotency, the state machine, webhook signatures, and reconciliation. They run against an in-memory H2 database, so no setup needed: `mvn test`.
- **CI:** `.github/workflows/ci.yml` — GitHub Actions runs the tests + a frontend build on every push.
- **Live deployment:** frontend on **Vercel**, backend (via a `Dockerfile`) on **Render**, MySQL on **Aiven**. All secrets are environment variables — nothing sensitive is in the code.
- **Deployment war stories:** `DEPLOYMENT_NOTES.md` documents every real problem hit while deploying (CORS, JDBC URL format, SPA routing, etc.) and how it was solved — a great read.

---

## A suggested learning path for your friend

Do it in this order — each step builds on the last:

1. **Play with the live demo** (link in `README.md`) — register, add money, send money, view history. Get the _feel_ first.
2. **Read Parts 0–5 of this file** — the concepts.
3. **Open `entity/` and read the 7 entities** — understand the nouns. Each has a teaching comment.
4. **Read `TransferService.java` while re-reading Part 6** — this is the single most important file; it ties everything together.
5. **Trace login:** `Login.jsx` → `authService.js` → `AuthController.java` → `AuthService.java` → `JwtService.java`.
6. **Read the webhook trio** (`webhook/`) and the **reconciliation folder** — the "senior" parts of the project.
7. **Skim `INTERVIEW_PREP.md` and `CODE_WALKTHROUGH.md`** — they go even deeper on the _why_ behind each decision.

**One sentence to hold onto the whole time:**

> _The frontend shows screens; the backend enforces every rule; money is moved atomically as balanced double-entry ledger records; and payments are confirmed by signed webhooks, then audited by daily reconciliation._

Everything else is detail. Welcome to LedgerPay. 🚀

---

_Companion docs: `README.md` · `PROJECT_SPEC.md` · `ARCHITECTURE_DIAGRAMS.md` · `INTERVIEW_PREP.md` · `CODE_WALKTHROUGH.md` · `DEPLOYMENT_NOTES.md`_
