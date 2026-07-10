# Payment Wallet & Reconciliation System — Project Specification

> **How to use this file with Copilot:** Keep this file open in your editor, or paste it into Copilot Chat at the start of a session, or (best option) save it as `.github/copilot-instructions.md` in your repo root — VS Code/Visual Studio/JetBrains automatically load that file into every Copilot Chat request. Then ask Copilot to help with **one task at a time** from the checklist near the bottom. Do not ask it to "build the whole project" in one prompt — go phase by phase, and always ask it to explain back to you what the code does before you accept it.

---

## 1. Project Overview

**What I'm building:** A digital wallet application (like a mini Paytm) where users can:
- Register/login securely
- Add money to their wallet using Razorpay (test mode)
- Send money to another user inside the app
- View their transaction history
- Have their transactions automatically checked every day against Razorpay's records to catch mismatches (this is called reconciliation)

**Why this project exists:** I am a 1-YOE Java Full Stack Developer (Spring Boot + React) with prior experience integrating Razorpay in an internship. Most junior developer portfolios have generic CRUD apps or AI chatbot clones — this project is meant to demonstrate real backend engineering depth (financial correctness, idempotency, webhook handling, reconciliation) that most junior candidates cannot show.

**Who it's for:** This is a resume/portfolio project. It does not need to support real money or real users — it needs to be technically correct, well-tested, deployed live, and something I can explain in complete depth in an interview.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java, Spring Boot, Spring Web, Spring Security, Spring Data JPA |
| Database (local dev) | MySQL |
| Database (deployed demo) | MySQL if available on host, otherwise PostgreSQL (JPA makes switching easy — only the dialect/driver config changes) |
| Auth | JWT (access token + refresh token) |
| Payment Gateway | Razorpay — **Test Mode only** (no real money, no KYC needed) |
| Frontend | React, Axios, React Router |
| Testing | JUnit 5, Mockito (backend), basic integration tests hitting a test DB (H2 or Testcontainers) |
| CI/CD | GitHub Actions (run tests + build on every push) |
| Backend Hosting | Render (free tier) |
| Frontend Hosting | Vercel or Netlify (free tier) |
| Version Control | Git + GitHub |

**Total cost: ₹0.** Razorpay Test Mode is free and needs no business verification. Render/Vercel free tiers need no credit card. The only optional cost is a custom domain, which is not needed.

---

## 3. Core Concepts I Must Understand (not just copy-paste)

These are the concepts an interviewer will drill into. I must be able to explain each one in my own words:

1. **Idempotency** — If the same "add money" or "send money" request is accidentally sent twice (e.g., user double-clicks, or network retries), the system must NOT process it twice. Solution: the client generates a unique `Idempotency-Key` (a UUID) per request. The server stores this key with the transaction. If a request arrives with a key that's already been processed, return the original result instead of creating a new transaction.

2. **Double-entry ledger** — Never store "balance" as a single number that gets directly edited. Instead, every transaction creates **ledger entries**: a DEBIT from one wallet and a CREDIT to another, which must always net to zero. The wallet's current balance is calculated (or cached) from the sum of its ledger entries. This makes the system auditable — I can always trace exactly why a balance is what it is.

3. **Transaction state machine** — Every transaction moves through defined states: `CREATED → PENDING → SUCCESS` or `CREATED → PENDING → FAILED`. A `SUCCESS` transaction can later move to `REVERSED` (refund). The code must never allow an illegal jump (e.g., `CREATED → SUCCESS` directly, skipping verification).

4. **Webhooks as source of truth, not the browser redirect** — After a Razorpay checkout, the browser gets redirected back to my app — but I must NOT trust that redirect alone to mark a payment as successful (a user could fake that call). The real confirmation comes from Razorpay calling my server directly via a **webhook**. I must verify the webhook's signature (HMAC SHA256 using my webhook secret) before trusting it.

5. **Reconciliation** — A scheduled job (can run manually via an endpoint, or on a timer) that compares my database's "successful" transactions against Razorpay's own records (via their API) for the same period, and flags any mismatches (e.g., I think it succeeded but Razorpay says it failed, or vice versa). This is the part almost no junior project has — it's the biggest differentiator.

---

## 4. Database Schema

```sql
-- Users
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(15),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Wallets (one per user)
CREATE TABLE wallets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    balance DECIMAL(15,2) NOT NULL DEFAULT 0.00, -- cached value, derived from ledger_entries
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Transactions (top-ups and transfers)
CREATE TABLE transactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    type VARCHAR(20) NOT NULL,        -- TOPUP, TRANSFER, WITHDRAWAL
    status VARCHAR(20) NOT NULL,      -- CREATED, PENDING, SUCCESS, FAILED, REVERSED
    sender_wallet_id BIGINT,          -- null for TOPUP
    receiver_wallet_id BIGINT,        -- null for WITHDRAWAL
    amount DECIMAL(15,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    razorpay_order_id VARCHAR(100),
    razorpay_payment_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_wallet_id) REFERENCES wallets(id),
    FOREIGN KEY (receiver_wallet_id) REFERENCES wallets(id)
);

-- Ledger entries (double-entry bookkeeping)
CREATE TABLE ledger_entries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    entry_type VARCHAR(10) NOT NULL,  -- DEBIT or CREDIT
    amount DECIMAL(15,2) NOT NULL,
    balance_after DECIMAL(15,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    FOREIGN KEY (wallet_id) REFERENCES wallets(id)
);

-- Raw webhook events received from Razorpay
CREATE TABLE webhook_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    razorpay_event_id VARCHAR(100) UNIQUE NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload_json TEXT NOT NULL,
    signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
);

-- Reconciliation run logs
CREATE TABLE reconciliation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_date DATE NOT NULL,
    total_checked INT NOT NULL,
    mismatches_found INT NOT NULL,
    mismatch_details_json TEXT,
    status VARCHAR(20) NOT NULL, -- COMPLETED, FAILED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 5. API Endpoints

### Auth
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Create a new user + auto-create their wallet |
| POST | `/api/auth/login` | Returns JWT access token + refresh token |
| POST | `/api/auth/refresh` | Get a new access token using refresh token |

### Wallet
| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/wallet/balance` | Get current user's wallet balance |
| GET | `/api/wallet/transactions?page=&status=` | Paginated transaction history |
| GET | `/api/wallet/transactions/{id}` | Single transaction detail |
| POST | `/api/wallet/topup/initiate` | Creates a Razorpay order, returns order_id to frontend for checkout. Requires `Idempotency-Key` header. |
| POST | `/api/wallet/transfer` | Send money to another user by email/ID. Requires `Idempotency-Key` header. |

### Webhook (called by Razorpay, not by frontend)
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/webhooks/razorpay` | Receives payment events. Must verify `X-Razorpay-Signature` header before processing. |

### Admin / Reconciliation
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/admin/reconciliation/run` | Manually trigger a reconciliation check (also runnable as a scheduled job) |
| GET | `/api/admin/reconciliation/logs` | View past reconciliation run results |

### Health
| Method | Endpoint | Description |
|---|---|---|
| GET | `/actuator/health` | For uptime checks / deployment health |

---

## 6. Razorpay Integration Notes

- Sign up at razorpay.com, generate **Test Mode** API keys immediately (no KYC or website verification needed for test mode).
- Test card: `4111 1111 1111 1111`, any future expiry, any CVV.
- Test UPI: use `success@razorpay` as the UPI ID to simulate a successful payment, or a failing test ID to simulate failure.
- Set up a webhook in the Razorpay dashboard pointing to my deployed backend's `/api/webhooks/razorpay` endpoint. Use a tool like `ngrok` to expose localhost during local development/testing.
- Never trust the frontend's "payment success" callback alone — always confirm via the webhook (or a server-to-server verification call to Razorpay's API) before crediting a wallet.
- Store the webhook secret in an environment variable, never hard-coded.

---

## 7. Security Requirements

- Passwords hashed with BCrypt — never stored in plain text.
- JWT access tokens short-lived (e.g., 15 min); refresh tokens longer-lived and revocable.
- All secrets (DB password, JWT secret, Razorpay keys, webhook secret) in environment variables — never committed to Git. Add a `.env` file to `.gitignore`.
- Validate all incoming request bodies (amounts must be positive, required fields present, etc.).
- Verify Razorpay webhook signatures on every incoming webhook call — reject anything that fails verification.
- Rate-limit sensitive endpoints like login and transfer if time allows.

---

## 8. Testing Requirements

- Unit tests for: idempotency key handling, ledger entry creation (debit/credit must always net to zero), transaction state transitions (valid vs invalid transitions), webhook signature verification logic.
- Integration tests for: full top-up flow (order creation → simulated webhook → balance update), full transfer flow, duplicate request handling (same idempotency key sent twice should not double-process).
- Cover both happy paths and failure paths (insufficient balance, invalid signature, duplicate request, wallet not found).
- Use an in-memory or test database (H2 or Testcontainers) for integration tests — never test against the real dev database.

---

## 9. Non-Functional Requirements

- **Logging:** Structured logs with a correlation/request ID so a single transaction's full journey can be traced through logs.
- **CI/CD:** GitHub Actions workflow that runs `mvn test` on every push/PR before allowing merge.
- **Live demo:** Must be deployed and clickable — not just a GitHub repo. Include demo login credentials in the README.
- **Documentation:** README must explain the architecture, the database schema, why idempotency/ledger/reconciliation were implemented the way they were, and what trade-offs were made. This documentation is as important as the code itself for interviews.
- **No over-engineering:** Keep the domain (wallet/payments) simple, but make the engineering around it (tests, error handling, structure) solid. Do not add unnecessary technologies just to pad the resume.

---

## 10. Build Plan — Work Through These Phases One at a Time

Do not ask Copilot to build multiple phases at once. Finish and understand each phase before moving to the next.

### Phase 1 — Foundation (aim: 3–4 days)
- [ ] Set up Spring Boot project (Spring Web, Spring Data JPA, Spring Security, MySQL Driver, Validation)
- [ ] Set up React project with routing
- [ ] Create database schema (entities + migrations)
- [ ] Implement register/login with JWT (access + refresh tokens)
- [ ] Auto-create a wallet when a user registers

### Phase 2 — Core Wallet Features (aim: 5 days)
- [ ] Get balance endpoint
- [ ] Transaction history endpoint (with pagination)
- [ ] Razorpay test mode account + sandbox keys set up
- [ ] Top-up initiate endpoint (creates Razorpay order)
- [ ] Frontend Razorpay checkout integration
- [ ] Transfer money between users (basic version, without idempotency yet)

### Phase 3 — The Hard Part: Idempotency, Webhooks, State Machine (aim: 5–6 days)
- [ ] Add `Idempotency-Key` handling to top-up and transfer endpoints
- [ ] Implement transaction state machine with validated transitions
- [ ] Build the ledger entry system (every transaction produces balanced debit/credit entries)
- [ ] Implement `/api/webhooks/razorpay` with signature verification
- [ ] Test webhook flow locally using ngrok
- [ ] Handle duplicate webhook delivery (Razorpay may send the same event more than once)

### Phase 4 — Reconciliation + Tests (aim: 5 days)
- [ ] Build the reconciliation job: fetch Razorpay's payment records for a date range, compare against local `transactions` table, log mismatches
- [ ] Write unit tests for idempotency, ledger balancing, state transitions
- [ ] Write integration tests for full top-up and transfer flows
- [ ] Set up GitHub Actions CI to run tests automatically

### Phase 5 — Deploy + Polish (aim: 4–5 days)
- [ ] Deploy backend to Render, frontend to Vercel/Netlify
- [ ] Set up production environment variables (never commit secrets)
- [ ] Confirm webhook works against the deployed URL
- [ ] Write the README (architecture, schema diagram, decisions, trade-offs, demo credentials)
- [ ] Do a final self-review: can I explain every part of this out loud without looking at the code?

---

## 11. Suggested Folder Structure

```
wallet-app/
├── backend/
│   ├── src/main/java/com/wallet/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   ├── dto/
│   │   ├── security/        (JWT filter, config)
│   │   ├── webhook/         (Razorpay webhook handling)
│   │   ├── reconciliation/  (scheduled job + logic)
│   │   └── exception/       (global exception handling)
│   └── src/test/java/com/wallet/
├── frontend/
│   └── src/
│       ├── components/
│       ├── pages/
│       ├── services/ (Axios API calls)
│       └── context/  (auth context)
├── .github/workflows/ci.yml
└── README.md
```

---

## 12. Working With Copilot — Ground Rules

- Give Copilot one checklist item at a time, referencing the relevant section number above (e.g., "Using section 4 and 5, help me build the `/api/wallet/topup/initiate` endpoint").
- After Copilot generates code, ask it: "Explain what this code does, line by line" — if I can't restate the explanation myself, don't move on yet.
- Never accept generated code for the idempotency, ledger, webhook, or reconciliation logic without fully understanding it — these are exactly what will be asked about in interviews.
- Ask Copilot to generate tests alongside each feature, not as an afterthought at the end.
