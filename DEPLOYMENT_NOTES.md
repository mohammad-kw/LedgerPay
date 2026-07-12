# LedgerPay — Deployment Problems & Solutions Log

> A real, honest record of every problem hit while deploying LedgerPay to production (Aiven MySQL + Render backend + Vercel frontend), and exactly how each was solved. Great "walk me through deploying it / tell me about a debugging challenge" interview material — each entry has a **symptom → root cause → fix → lesson**.

**Deployment stack:** React/Vite → **Vercel** · Spring Boot (Docker) → **Render** · MySQL → **Aiven**

---

## 0. Pre-deployment prep (the setup that avoided later pain)

Before deploying, we made the app "12-factor" ready so nothing was hard-coded:

- `server.port=${PORT:8080}` — Render injects the port.
- All secrets read from **environment variables** with local-dev fallbacks.
- **CORS origins** read from `CORS_ALLOWED_ORIGINS` env var.
- Frontend API base URL read from `VITE_API_BASE_URL`.
- Added a **Dockerfile** (multi-stage) so Render builds reliably.

**Lesson:** Externalizing config _before_ deploying meant the only prod work was setting env vars, not changing code.

---

## 1. GitHub push — Personal Access Token missing `workflow` scope

**Symptom:**

```
! [remote rejected] main -> main (refusing to allow a Personal Access Token
to create or update workflow `.github/workflows/ci.yml` without `workflow` scope)
```

**Root cause:** Pushing a repo that contains a `.github/workflows/*.yml` file requires the PAT to have the **`workflow`** scope, not just `repo`.

**Fix:** Edited the existing token on GitHub → checked the **`workflow`** scope → re-pushed. (Token value stays the same, so the remote URL kept working.)

**Lesson:** A `repo`-scoped token can't push CI workflow files; you also need `workflow`.

---

## 2. Wrong GitHub account pushed (403 denied)

**Symptom:**

```
remote: Permission to mohammad-kw/LedgerPay.git denied to mohammadkw0.
fatal: ... The requested URL returned error: 403
```

**Root cause:** The machine had a **company** GitHub credential (`mohammadkw0`) cached in Windows Credential Manager, so git tried to push to the personal repo (`mohammad-kw`) using the wrong identity.

**Fix:** Avoided touching the cached company credential entirely by embedding a **Personal Access Token for the personal account directly in the remote URL** for this one repo:

```
git remote set-url origin https://mohammad-kw:<TOKEN>@github.com/mohammad-kw/LedgerPay.git
```

Also set a repo-local identity so commit author was correct:

```
git config --local user.name "mohammad-kw"
git config --local user.email "…@gmail.com"
```

**Lesson:** On a machine with two GitHub accounts, use a per-repo token in the remote URL (or per-repo credentials) to avoid the global credential silently using the wrong account.

---

## 3. CI failed — pnpm needed Node 22, runner had Node 20

**Symptom (GitHub Actions):**

```
warn: This version of pnpm requires at least Node.js v22.13
Error [ERR_UNKNOWN_BUILTIN_MODULE]: No such built-in module: node:sqlite
```

**Root cause:** The CI used `corepack`, which pulled the **latest pnpm (11.x)** that requires Node 22, but the workflow installed **Node 20** → crash.

**Fix (two parts):**

1. **Pinned pnpm** in `frontend/package.json`: `"packageManager": "pnpm@9.15.0"` (works on Node 20).
2. Bumped the CI Node version to **22** for future-proofing.

**Lesson:** Pin your package-manager version so a floating "latest" can't silently break CI when its runtime requirements jump.

---

## 4. CI failed — "packages field missing or empty"

**Symptom:**

```
ERROR  packages field missing or empty
```

**Root cause:** `frontend/pnpm-workspace.yaml` existed but had no valid `packages:` list, so pnpm treated the folder as a **monorepo root with zero member packages** and errored.

**Fix:** The frontend is a **single package, not a monorepo**, so we **deleted `pnpm-workspace.yaml`** entirely, and relaxed the CI install to `--no-frozen-lockfile` so a lockfile mismatch couldn't fail the build.

**Lesson:** Don't keep a workspace file for a single-package project — it makes pnpm expect a monorepo.

---

## 5. Render — MySQL driver rejected the connection URL

**Symptom:**

```
Driver com.mysql.cj.jdbc.Driver claims to not accept jdbcUrl,
mysql://avnadmin:****@…aivencloud.com:16729/defaultdb?ssl-mode=REQUIRED
```

**Root cause:** We pasted **Aiven's raw connection URI** into `DB_URL`. The JDBC driver needs a different format: a `jdbc:mysql://` prefix, **no embedded credentials**, and JDBC-style SSL params (not the CLI's `ssl-mode`).

**Fix:** Split it into proper env vars:

```
DB_URL = jdbc:mysql://…aivencloud.com:16729/defaultdb?useSSL=true&requireSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true
DB_USERNAME = avnadmin
DB_PASSWORD = ****
```

**Lesson:** A database provider's "connection URI" is **not** a JDBC URL. JDBC needs `jdbc:` prefix, credentials as separate properties, and driver-specific SSL params.

---

## 6. Render — "Communications link failure" (couldn't connect at all)

**Symptom:**

```
org.hibernate.exception.JDBCConnectionException: Unable to open JDBC
Connection for DDL execution [Communications link failure]
```

**Root cause:** The URL format was now valid, but the **SSL handshake/params** weren't right for Aiven (which _requires_ SSL), so the actual connection couldn't be established.

**Fix:** Used the Connector/J-native SSL param and public-key retrieval:

```
?sslMode=REQUIRED&serverTimezone=UTC&allowPublicKeyRetrieval=true
```

Also confirmed the Aiven service was **Running** and had **no IP allowlist** blocking Render (free tier has no fixed outbound IP).

**Lesson:** "Communications link failure" = a network/SSL problem (not URL or auth). Read the deepest `Caused by:` and match SSL params to what the DB host requires.

---

## 7. Backend "403 / access denied" at the root URL — _not actually an error_

**Symptom:** Visiting `https://…onrender.com/` showed `HTTP ERROR 403`.

**Root cause:** `SecurityConfig` has `anyRequest().authenticated()`, so hitting `/` (a non-public path) with no JWT correctly returns 403. The app was **working fine**.

**Fix:** Nothing to fix — tested a **public** endpoint instead (`/api/auth/login`), which returned 200 with a token.

**Lesson:** A 403 on an unmapped/protected path can be _correct_ security behavior, not a deployment failure. Test a known-public route to verify liveness.

---

## 8. `/actuator/health` returned 500

**Symptom:**

```json
{
  "status": 500,
  "message": "An unexpected error occurred",
  "path": "/actuator/health"
}
```

**Root cause:** The **Spring Boot Actuator dependency was never added**, so `/actuator/health` didn't exist — the request fell through to the catch-all `GlobalExceptionHandler` → generic 500. (SecurityConfig even permitted the path, but nothing served it.)

**Fix:** Added `spring-boot-starter-actuator` to `pom.xml` and exposed only health:

```
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=when-authorized
```

**Lesson:** `/actuator/**` only exists if the actuator starter is on the classpath. A 500 on it usually means the dependency is missing.

---

## 9. Frontend 404 on refresh (SPA routing)

**Symptom:** The site loaded once, but **refreshing** on `/login` (or any route) showed Vercel's **404 Not Found**.

**Root cause:** Classic SPA problem — React Router handles routes client-side, but on refresh the browser asks Vercel's server for `/login` directly, and there's no such file → 404.

**Fix:** Added `frontend/vercel.json` rewriting all paths to `index.html` so React Router handles routing:

```json
{ "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }] }
```

**Lesson:** Any client-side-routed SPA needs a host-level "serve index.html for all routes" rewrite.

---

## 10. Login failed in the browser but worked from the terminal — CORS

**Symptom:** Frontend showed "login failed", even on mobile data (ruling out the corporate Zscaler proxy). But calling the backend **directly from a terminal** returned `200` + a valid token.

**Root cause:** **CORS.** Browsers enforce CORS; terminals don't. Sending the login request _with_ the frontend's `Origin` header reproduced the failure:

```
POST /api/auth/login  (Origin: https://ledgerpay-nine.vercel.app)  → 403
Preflight OPTIONS                                                   → 403 (origin not allowed)
```

So the backend was rejecting the Vercel origin.

**Fix:** Set the allowed origin on Render (`CORS_ALLOWED_ORIGINS`). The final gotcha: the env var was initially named/valued slightly wrong — it had to **exactly** match the code's property and be the exact origin (**https, no trailing slash, no path**).

**Diagnosis trick used:** reproduce CORS from a terminal by adding the `Origin` header and sending an `OPTIONS` preflight — if that 403s, it's CORS, not credentials.

**Lesson:** "Works in curl/terminal but not the browser" = almost always CORS. The allowed origin must match the frontend URL exactly (scheme + host, no trailing slash).

---

## 11. DevTools blocked by the corporate isolation browser

**Symptom:** Couldn't open the Network tab (F12/right-click disabled) because the site opened in a **Zscaler isolation browser**, making browser-side debugging impossible.

**Root cause:** Corporate security tooling sandboxed the page.

**Fix:** Bypassed the browser entirely — tested the live backend **directly from the terminal** (`Invoke-WebRequest`) to get real status codes and responses, and reproduced CORS with a manual `Origin` header.

**Lesson:** When browser DevTools aren't available, curl / `Invoke-WebRequest` against the live API is a powerful substitute — it isolates backend vs. browser/CORS problems.

---

## 12. Top-up failed live — "payment provider is currently unavailable"

**Symptom:** On the deployed site, "Add money" → "Continue to payment" → error, because it tried to reach **Razorpay**, which isn't KYC-activated.

**Root cause:** The **mock top-up** button was gated behind `import.meta.env.DEV`, so it was **tree-shaken out of the production build** — leaving only the (failing) Razorpay path.

**Fix:** Introduced a build-time flag `VITE_ENABLE_MOCK_TOPUP`. When set (on Vercel), the modal shows the **mock "Add money"** button and hides the Razorpay button; the backend enables the matching route via `MOCK_TOPUP_ENABLED=true` (on Render). The mock path reuses the **exact same ledger + state-machine logic** as the real webhook flow.

**Lesson:** `import.meta.env.DEV` code never ships to prod. For a demo feature that must exist in production, gate it behind an explicit build-time env flag instead.

---

## Cross-cutting lessons

1. **"Works in terminal, fails in browser" → CORS.** Reproduce with an `Origin` header + `OPTIONS` preflight.
2. **A provider's connection URI ≠ a JDBC URL.** Reformat with `jdbc:` prefix, separate credentials, driver SSL params.
3. **A 403/500 isn't always a bug** — `/` 403 is correct security; `/actuator/health` 500 was a missing dependency.
4. **Env vars must match exactly** — name, scheme, host, no trailing slash. One character (`ORIGIN` vs `ORIGINS`) broke CORS.
5. **Env vars bake in at build time for Vite** — you must redeploy the frontend after changing them.
6. **Pin tool versions** (pnpm) so CI isn't broken by a floating "latest".
7. **Externalize config first** — the smooth parts of this deploy were the ones we prepared for in advance.

---

_Companion to `README.md`, `INTERVIEW_PREP.md`, `CODE_WALKTHROUGH.md`, and `ARCHITECTURE_DIAGRAMS.md`._
