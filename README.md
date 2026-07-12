# LedgerPay — Digital Wallet & Reconciliation System

A production-minded digital wallet (a mini-Paytm) that demonstrates real backend engineering depth: **financial correctness** (double-entry ledger), **idempotency**, **webhook-driven payment confirmation**, and **daily reconciliation** against the payment gateway — the kinds of problems most junior portfolios never touch.

> **Status:** Live in production. Backend + frontend feature-complete, 52 tests passing, CI green. Razorpay runs in **Test Mode**; the deployed demo uses an equivalent mock top-up (same ledger + state-machine path) because the Razorpay account isn't KYC-activated.

## 🚀 Live demo

**App:** https://ledgerpay-nine.vercel.app

**Demo credentials:**

| Role  | Email                 | Password                                    |
| ----- | --------------------- | ------------------------------------------- |
| Admin | `admin@ledgerpay.com` | `Admin@12345`                               |
| User  | _register your own_   | must have upper + lower + digit, 8–72 chars |

> ⏳ **First load may take ~30–50 seconds.** The backend runs on Render's free tier, which sleeps after inactivity and needs a moment to wake on the first request. Subsequent requests are fast.

**Try this flow:** log in as admin → **Add money** (instant demo credit) → register a second user in another tab → **Send money** between them → view transaction history → open the **Admin dashboard** for metrics + reconciliation.

**Stack:** React/Vite on Vercel · Spring Boot (Docker) on Render · MySQL on Aiven.

---

## Table of Contents

1. [What it does](#what-it-does)
2. [Tech stack](#tech-stack)
3. [Architecture](#architecture)
4. [Core engineering concepts](#core-engineering-concepts)
5. [Database schema](#database-schema)
6. [API reference](#api-reference)
7. [Running locally](#running-locally)
8. [Configuration (environment variables)](#configuration-environment-variables)
9. [Testing](#testing)
10. [Deployment](#deployment)
11. [Trade-offs & future work](#trade-offs--future-work)
12. [Project docs](#project-docs)

---

## What it does

- **Register / login** securely (JWT access + refresh tokens, BCrypt password hashing).
- **Add money** to the wallet via Razorpay (test mode), confirmed by a **signature-verified webhook** — never by the browser redirect.
- **Send money** to another user, recorded as an **atomic double-entry ledger** transaction.
- **View transaction history** and individual transaction details (IDOR-safe).
- **Admin dashboard** — role-gated metrics, transaction feed, and manual reconciliation trigger.
- **Daily reconciliation** — a scheduled job compares our records against the gateway's and flags any drift (read-only; detects, never auto-fixes).

---

## Tech stack

| Layer    | Technology                                                       |
| -------- | ---------------------------------------------------------------- |
| Backend  | Java 21, Spring Boot 3.3.4 (Web, Security, Data JPA)             |
| Database | MySQL (dev & prod), H2 (tests)                                   |
| Auth     | JWT (stateless access token + revocable, rotating refresh token) |
| Payments | Razorpay SDK 1.4.9 — **Test Mode**                               |
| Frontend | React 18, Vite 6, React Router 6, Axios, Recharts                |
| Testing  | JUnit 5, Mockito (52 tests)                                      |
| CI       | GitHub Actions (backend `mvn verify` + frontend build)           |
| Hosting  | Render (backend) + Vercel/Netlify (frontend)                     |

---

## Architecture

```
  React (Vite)  ──HTTPS/JSON, Bearer JWT──▶  Spring Boot  ──JPA──▶  MySQL
        │                                        ▲
        │ Razorpay Checkout                      │ payment.captured (signed webhook)
        ▼                                        │
     Razorpay (test) ───────────────────────────┘
        ▲
        └── daily reconciliation reads gateway records
```

**Layered backend:** `Controller` (thin HTTP) → `Service` (business logic + `@Transactional`) → `Repository` (Spring Data JPA) → `Entity`/`DTO`. Cross-cutting: `CorrelationIdFilter` (per-request id), `JwtAuthenticationFilter` (auth), `GlobalExceptionHandler` (errors → clean JSON).

Package map (`com.wallet`): `controller · service · repository · entity · dto · security · webhook · reconciliation · exception · logging · config`.

> Full diagrams in [`ARCHITECTURE_DIAGRAMS.md`](./ARCHITECTURE_DIAGRAMS.md).

---

## Core engineering concepts

- **Idempotency** — mutating endpoints accept an `Idempotency-Key` header; a repeat (double-click, retry) returns the original result. Defended twice: app-level check + DB `UNIQUE` constraint (closes the check-then-insert race).
- **Double-entry ledger** — every money movement writes two equal-and-opposite entries (debit + credit) that net to zero; the wallet balance is a cache validated against the ledger. Any imbalance is a detectable bug.
- **Transaction state machine** — transactions move only through allowed states (`CREATED → SUCCESS → REVERSED`, etc.); illegal transitions are rejected, so verification can't be skipped.
- **Webhooks as source of truth** — top-ups are credited only after an **HMAC-SHA256 signature-verified** webhook; the browser "success" redirect is never trusted. Duplicate events are ignored via a unique event id.
- **Reconciliation** — a daily scheduled job (and an admin trigger) performs a two-directional compare between our top-ups and the gateway's payments, classifying mismatches (`MISSING_LOCALLY`, `MISSING_AT_GATEWAY`, `STATUS_MISMATCH`, `AMOUNT_MISMATCH`). The gateway is behind an interface so the engine is testable and swappable via config.
- **All money math uses `BigDecimal`** (never `double`), matching `DECIMAL(15,2)` columns.

---

## Database schema

| Table                 | Purpose                                                         |
| --------------------- | --------------------------------------------------------------- |
| `users`               | Account + BCrypt password hash + `role` (USER/ADMIN)            |
| `wallets`             | One per user; cached `balance` (DECIMAL)                        |
| `transactions`        | One row per money event; type, status, amount, idempotency key  |
| `ledger_entries`      | Double-entry rows (DEBIT/CREDIT) with `balance_after`           |
| `refresh_tokens`      | Revocable refresh tokens (rotation, logout)                     |
| `webhook_events`      | Audit of received webhooks; unique `razorpay_event_id` (dedupe) |
| `reconciliation_logs` | One row per reconciliation run (counts + details JSON)          |

Relationships: `User 1—1 Wallet 1—* LedgerEntry *—1 Transaction`; `User 1—* RefreshToken`.

---

## API reference

Base path: `/api`. All non-auth, non-webhook routes require `Authorization: Bearer <accessToken>`. Mutating routes accept an `Idempotency-Key` header.

### Auth — `/api/auth`

| Method | Path        | Description                          |
| ------ | ----------- | ------------------------------------ |
| POST   | `/register` | Create user + wallet (atomic)        |
| POST   | `/login`    | Returns access + refresh tokens      |
| POST   | `/refresh`  | Rotate refresh token, issue new pair |

### Wallet — `/api/wallet`

| Method | Path                 | Description                                       |
| ------ | -------------------- | ------------------------------------------------- |
| GET    | `/balance`           | Current wallet balance                            |
| GET    | `/transactions`      | Paginated transaction history                     |
| GET    | `/transactions/{id}` | Single transaction (404 if not yours — IDOR-safe) |
| POST   | `/topup/initiate`    | Create a Razorpay order                           |
| POST   | `/topup/mock`        | Dev-mode top-up (bypasses unactivated gateway)    |
| POST   | `/transfer`          | Send money to another user                        |

### Webhooks — `/api/webhooks`

| Method | Path        | Description                                          |
| ------ | ----------- | ---------------------------------------------------- |
| POST   | `/razorpay` | Gateway callback; HMAC-verified, then credits wallet |

### Admin — `/api/admin` (requires `ROLE_ADMIN`)

| Method | Path                   | Description                       |
| ------ | ---------------------- | --------------------------------- |
| GET    | `/metrics`             | Aggregate platform metrics        |
| GET    | `/transactions`        | All transactions (admin view)     |
| POST   | `/reconciliation/run`  | Trigger reconciliation for a date |
| GET    | `/reconciliation/logs` | Past reconciliation runs          |

---

## Running locally

### Prerequisites

- Java 21, Maven
- MySQL 8 (running locally)
- Node 20 + pnpm (via `corepack enable`)

### 1. Database

```sql
CREATE DATABASE ledgerpay;
```

### 2. Backend

```powershell
# from repo root — set env vars (or use backend/.env + run-backend.ps1)
cd backend
mvn clean compile
mvn spring-boot:run
```

Backend starts on `http://localhost:8080`. Hibernate auto-creates tables (`ddl-auto=update`).

### 3. Frontend

```powershell
cd frontend
corepack pnpm install
corepack pnpm dev
```

Frontend starts on `http://localhost:5173`.

---

## Configuration (environment variables)

All secrets are read from environment variables with local-dev fallbacks — nothing sensitive is committed. See `backend/.env.example`.

| Variable                                        | Purpose                                |
| ----------------------------------------------- | -------------------------------------- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`        | MySQL connection                       |
| `JWT_SECRET`                                    | Signing key for access tokens          |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET`       | Gateway credentials (test mode)        |
| `RAZORPAY_WEBHOOK_SECRET`                       | HMAC secret for webhook verification   |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` / `ADMIN_NAME` | Seeded admin account                   |
| `PORT`                                          | Server port (injected by host in prod) |

Frontend uses `VITE_API_URL` for the backend base URL.

---

## Testing

```powershell
cd backend
mvn test
```

**52 tests** (JUnit 5 + Mockito) covering auth, transfers, ledger balancing, idempotency, the state machine, webhook signature verification, and the reconciliation engine. Tests run against an in-memory **H2** database (MySQL-compatible mode), so no external services are needed.

> On Windows/PowerShell, a non-zero exit code can be a false alarm from stderr — confirm success by looking for `BUILD SUCCESS` and `Failures: 0, Errors: 0`.

---

## Deployment

**Frontend (Vercel/Netlify):** set `VITE_API_URL` to the deployed backend URL and deploy the `frontend/` folder.

**Backend (Render):**

1. Provision a hosted MySQL database.
2. Set all env vars from the table above (DB, JWT, Razorpay, admin) in the host dashboard.
3. `server.port` already reads `${PORT}` — no code change needed.
4. Add the deployed **frontend origin** to the backend's allowed CORS origins.
5. Point the Razorpay webhook at `https://<backend>/api/webhooks/razorpay`.

> Deployment externalizes configuration only (hosted DB, secrets, CORS, port) — all via environment variables, which the app was built to consume from day one.

---

## Trade-offs & future work

**Conscious trade-offs:**

- `ddl-auto=update` instead of Flyway migrations — fine for a solo project; Flyway is the production-correct choice.
- Cached balance alongside the ledger — a deliberate read-performance choice, guarded by atomic writes + reconciliation.
- Razorpay account never activated (KYC) — worked around with a mock top-up (reusing the real ledger/state-machine logic) and a gateway abstraction, so the external dependency never blocked the core engineering.

**Future work:** Flyway migrations, rate-limiting on login/transfer, withdrawal flow, email verification (registration OTP), Testcontainers (real MySQL in tests), pagination/filters on the admin feed, and completing real Razorpay once activated.

---

## Project docs

| File                                                     | Purpose                          |
| -------------------------------------------------------- | -------------------------------- |
| [`PROJECT_SPEC.md`](./PROJECT_SPEC.md)                   | Original specification           |
| [`ARCHITECTURE_DIAGRAMS.md`](./ARCHITECTURE_DIAGRAMS.md) | ASCII flow diagrams              |
| [`INTERVIEW_PREP.md`](./INTERVIEW_PREP.md)               | Deep design rationale & Q&A      |
| [`CODE_WALKTHROUGH.md`](./CODE_WALKTHROUGH.md)           | Key functions with code snippets |
| [`INTERVIEW_CHEATSHEET.md`](./INTERVIEW_CHEATSHEET.md)   | One-page recall                  |

---

_Built as a portfolio project to demonstrate financial-correctness engineering: idempotency, double-entry ledgers, webhook trust boundaries, and reconciliation._
