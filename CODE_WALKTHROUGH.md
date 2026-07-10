# LedgerPay — Code Walkthrough ("How did you build X?")

> **Purpose:** For every major function an interviewer might point at and ask _"how did you do this?"_, this file gives (1) a plain-English summary, (2) the real code snippet, and (3) the key lines to talk about. Snippets are trimmed for readability — logic matches the actual code.

**Contents**

1. Register + auto-create wallet
2. Login + JWT issuance
3. JWT authentication filter (how every request is authenticated)
4. Refresh token rotation
5. Idempotency (top-up & transfer)
6. Transfer — balance check + double-entry ledger (the big one)
7. Transaction state machine
8. Webhook signature verification (HMAC)
9. Webhook processing (top-up confirmation)
10. Reconciliation engine
11. Correlation-ID logging filter
12. RBAC + admin seeding
13. Axios token refresh (frontend)

---

## 1. Register + auto-create wallet

**What:** Create a user and their wallet together, atomically. Reject duplicate emails early.

```java
@Transactional
public RegisterResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.email())) {
        throw new DuplicateEmailException("An account with email '" + request.email() + "' already exists");
    }
    User user = User.builder()
            .name(request.name())
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password())) // BCrypt — never plain text
            .phone(request.phone())
            .build();
    user = userRepository.save(user);

    Wallet wallet = Wallet.builder().user(user).balance(BigDecimal.ZERO).build();
    walletRepository.save(wallet);

    return new RegisterResponse(user.getId(), user.getName(), user.getEmail());
}
```

**Talk about:** `@Transactional` = two inserts (User + Wallet) commit together or both roll back → no user ever ends up wallet-less (atomicity). `existsByEmail` gives a clean error before hitting the DB unique constraint. `passwordEncoder.encode` = BCrypt; the raw password is never stored.

---

## 2. Login + JWT issuance

**What:** Verify credentials (delegated to Spring Security), then mint an access token (JWT) + a refresh token.

```java
public AuthResponse login(LoginRequest request) {
    Authentication auth = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.email(), request.password()));
    User user = ((UserPrincipal) auth.getPrincipal()).getUser();
    return issueTokens(user);
}

private AuthResponse issueTokens(User user) {
    String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    String refreshTokenValue = UUID.randomUUID().toString();     // opaque, stored in DB
    RefreshToken refreshToken = RefreshToken.builder()
            .token(refreshTokenValue).user(user)
            .expiryDate(LocalDateTime.now().plus(Duration.ofMillis(jwtService.getRefreshTokenExpiryMs())))
            .build();
    refreshTokenRepository.save(refreshToken);
    return new AuthResponse(accessToken, refreshTokenValue, jwtService.getAccessTokenExpirySeconds());
}
```

The token itself:

```java
public String generateAccessToken(Long userId, String email, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(email)                    // "sub"
            .claim("uid", userId)
            .claim("role", role)               // used for UI routing; re-checked server-side
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(jwtProperties.accessTokenExpiryMs()))) // 15 min
            .signWith(signingKey)              // HMAC-SHA256
            .compact();
}
```

**Talk about:** We don't compare passwords ourselves — `AuthenticationManager` calls our `UserDetailsService` + BCrypt and throws `BadCredentialsException` (→401) on mismatch. Access token is **stateless** (verified by signature); refresh token is a **random opaque string stored in the DB** so it can be revoked. `role` is in the JWT only for client routing — the server re-checks it on every admin call.

---

## 3. JWT authentication filter (how every request is authenticated)

**What:** A filter that runs once per request, reads the `Bearer` token, verifies it, and populates the SecurityContext.

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        chain.doFilter(request, response);   // public endpoint — let SecurityConfig decide
        return;
    }
    String token = authHeader.substring(7);
    try {
        String email = jwtService.extractEmail(token);   // verifies signature + expiry
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            userRepository.findByEmail(email).ifPresent(user -> {
                UserPrincipal principal = new UserPrincipal(user);
                var authToken = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());   // 3rd arg = "already authenticated"
                SecurityContextHolder.getContext().setAuthentication(authToken);
            });
        }
    } catch (JwtException ex) {
        log.debug("Invalid JWT: {}", ex.getMessage());   // don't reject here — let SecurityConfig 401/403
    }
    chain.doFilter(request, response);
}
```

**Talk about:** The filter never rejects — it only _authenticates if it can_; `SecurityConfig`'s rules decide access afterward (clean separation). Passing `authorities` as the 3rd arg marks the token trusted because we already verified the signature. `OncePerRequestFilter` guarantees single execution.

---

## 4. Refresh token rotation

**What:** Each refresh revokes the old token and issues a brand-new pair.

```java
@Transactional
public AuthResponse refresh(String rawRefreshToken) {
    RefreshToken stored = refreshTokenRepository.findByToken(rawRefreshToken)
            .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not recognized"));
    if (stored.getRevoked()) throw new InvalidRefreshTokenException("Refresh token has been revoked");
    if (stored.getExpiryDate().isBefore(LocalDateTime.now()))
        throw new InvalidRefreshTokenException("Refresh token has expired");

    stored.setRevoked(true);                 // ROTATION: kill this one first
    refreshTokenRepository.save(stored);
    return issueTokens(stored.getUser());    // issue a fresh pair
}
```

**Talk about:** Rotation means a stolen refresh token works **once** — the next legitimate refresh invalidates it. We revoke _before_ issuing so a mid-failure can't leave the old one usable.

---

## 5. Idempotency (top-up & transfer)

**What:** Before doing work, check if this `Idempotency-Key` was already processed; if so, return the original result.

```java
var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
if (existing.isPresent()) {
    Transaction txn = existing.get();
    log.info("Idempotency-Key {} already processed -> returning existing txn id={}", idempotencyKey, txn.getId());
    return TransferResponse.from(txn, receiverEmail, /* current balance */ ...);
}
```

The key arrives as an HTTP **header**:

```java
@PostMapping("/transfer")
public ResponseEntity<TransferResponse> transfer(
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestHeader("Idempotency-Key") String idempotencyKey,   // header, not body
        @Valid @RequestBody TransferRequest request) { ... }
```

**Talk about:** App-level check + a `UNIQUE` constraint on `idempotency_key` as the hard backstop (closes the check-then-insert race — the second concurrent insert fails). Header because idempotency is a property of the request (Stripe/Razorpay convention). Client-generated because only the client knows "this is a retry."

---

## 6. Transfer — balance check + double-entry ledger (THE one)

**What:** Move money atomically: dedupe → validate → DEBIT sender + CREDIT receiver (net zero) → update both balances → mark SUCCESS. All in one transaction.

```java
@Transactional
public TransferResponse transfer(Long senderUserId, String receiverEmail, BigDecimal amount, String idempotencyKey) {
    // 1. Idempotency short-circuit (see §5) ...

    // 2. Resolve + validate
    Wallet senderWallet = walletRepository.findByUserId(senderUserId)
            .orElseThrow(() -> new WalletNotFoundException("No wallet for current user"));
    User receiver = userRepository.findByEmail(receiverEmail.trim().toLowerCase())
            .orElseThrow(() -> new InvalidTransferException("...recipient"));
    Wallet receiverWallet = walletRepository.findByUserId(receiver.getId())
            .orElseThrow(() -> new InvalidTransferException("...recipient"));
    if (senderWallet.getId().equals(receiverWallet.getId()))
        throw new InvalidTransferException("You cannot transfer money to yourself");

    // 3. THE balance check — compareTo, NOT equals (equals also compares scale)
    if (senderWallet.getBalance().compareTo(amount) < 0)
        throw new InsufficientBalanceException("Insufficient wallet balance");

    // 4. Create the transaction (CREATED)
    Transaction txn = transactionRepository.save(Transaction.builder()
            .idempotencyKey(idempotencyKey).type(TransactionType.TRANSFER)
            .status(TransactionStatus.CREATED)
            .senderWallet(senderWallet).receiverWallet(receiverWallet)
            .amount(amount).currency(senderWallet.getCurrency()).build());

    // 5. Double-entry ledger — DEBIT + CREDIT net to zero
    BigDecimal newSender   = senderWallet.getBalance().subtract(amount);
    BigDecimal newReceiver = receiverWallet.getBalance().add(amount);
    LedgerEntry debit  = LedgerEntry.builder().transaction(txn).wallet(senderWallet)
            .entryType(EntryType.DEBIT).amount(amount).balanceAfter(newSender).build();
    LedgerEntry credit = LedgerEntry.builder().transaction(txn).wallet(receiverWallet)
            .entryType(EntryType.CREDIT).amount(amount).balanceAfter(newReceiver).build();
    ledgerEntryRepository.saveAll(List.of(debit, credit));

    // 6. Update both cached balances (in the SAME transaction → can't diverge)
    senderWallet.setBalance(newSender);   walletRepository.save(senderWallet);
    receiverWallet.setBalance(newReceiver); walletRepository.save(receiverWallet);

    // 7. CREATED → SUCCESS, guarded by the state machine
    TransactionStateMachine.assertCanTransition(txn.getStatus(), TransactionStatus.SUCCESS);
    txn.setStatus(TransactionStatus.SUCCESS);
    transactionRepository.save(txn);

    return TransferResponse.from(txn, receiver.getEmail(), newSender);
}
```

**Talk about (this is the money question):**

- **One `@Transactional`** = the two ledger rows + two balance updates + status change all commit together or all roll back. **No window** where the sender is debited but receiver not credited.
- **`compareTo` not `equals`** for `BigDecimal`: `equals` also compares scale (`100.0` ≠ `100.00`); `compareTo` compares value.
- **Validate before any write** → a rejected transfer persists nothing (Spring rolls back on any `RuntimeException`), so we don't even try to write a "FAILED" row (it'd roll back anyway).
- **DEBIT + CREDIT net to zero** = the double-entry invariant → fully auditable.

---

## 7. Transaction state machine

**What:** Encode legal transitions; reject illegal jumps.

```java
private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED =
        new EnumMap<>(TransactionStatus.class);
static {
    ALLOWED.put(CREATED, Set.of(PENDING, SUCCESS, FAILED));
    ALLOWED.put(PENDING, Set.of(SUCCESS, FAILED));
    ALLOWED.put(SUCCESS, Set.of(REVERSED));
    ALLOWED.put(FAILED,   Set.of());     // terminal
    ALLOWED.put(REVERSED, Set.of());     // terminal
}
public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
}
public static void assertCanTransition(TransactionStatus from, TransactionStatus to) {
    if (!canTransition(from, to)) throw new IllegalStateTransitionException(from, to);
}
```

**Talk about:** A small, pure, dependency-free class = trivially unit-testable (22 tests). `EnumMap` is the efficient map for enum keys (array indexed by ordinal). Terminal states have empty sets → nothing can leave FAILED/REVERSED. `CREATED→SUCCESS` is allowed but only ever happens _after_ a signature-verified webhook, so verification is never skipped.

---

## 8. Webhook signature verification (HMAC)

**What:** Prove a webhook really came from Razorpay by recomputing HMAC-SHA256 over the raw body with the shared secret.

```java
public boolean isValid(String rawPayload, String signatureHeader) {
    if (signatureHeader == null || signatureHeader.isBlank()) return false;   // no signature → reject
    String expected = computeHmacSha256Hex(rawPayload, razorpayProperties.webhookSecret());
    // Constant-time comparison to avoid timing attacks
    return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signatureHeader.getBytes(StandardCharsets.UTF_8));
}

private String computeHmacSha256Hex(String message, String secret) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] hmac = mac.doFinal(message.getBytes(UTF_8));
    return HexFormat.of().formatHex(hmac);   // lowercase hex, like Razorpay
}
```

**Talk about (two subtle must-mention points):**

1. **Raw bytes** — we HMAC the exact body Razorpay sent. If we parsed & re-serialized the JSON, whitespace/key-order could change, bytes differ, HMAC fails. So the controller passes the body as a **raw String**, not a DTO.
2. **Constant-time compare** (`MessageDigest.isEqual`) — a naive `equals`/`==` bails at the first differing char, and the timing leak can let an attacker recover a valid signature byte-by-byte. HMAC = authenticity + integrity because only holders of the secret can produce a matching signature.

---

## 9. Webhook processing (top-up confirmation)

**What:** On a verified `payment.captured`, find the transaction, move it to SUCCESS, credit the wallet — idempotently (Razorpay may resend).

```java
// 1. Verify signature FIRST — reject anything that fails.
if (!signatureVerifier.isValid(rawBody, signatureHeader)) return reject();

// 2. Duplicate-delivery guard: unique razorpay_event_id → reprocessing is a no-op.
if (webhookEventRepository.existsByRazorpayEventId(eventId)) return alreadyProcessed();

// 3. Find our transaction by Razorpay order id, then move CREATED → SUCCESS (guarded)
Transaction txn = transactionRepository.findByRazorpayOrderId(orderId).orElseThrow(...);
TransactionStateMachine.assertCanTransition(txn.getStatus(), TransactionStatus.SUCCESS);
txn.setStatus(TransactionStatus.SUCCESS);
txn.setRazorpayPaymentId(paymentId);

// 4. CREDIT ledger entry + update cached balance (same @Transactional)
```

**Talk about:** Signature check before _anything_. Idempotent via unique `razorpay_event_id`. We store the raw event in `webhook_events` for audit/replay. The browser redirect is never trusted — only this verified server-to-server call credits money.

---

## 10. Reconciliation engine

**What:** For a day, compare local top-ups vs gateway payments (join on payment id), walk both directions, log the 4 mismatch types. Read-only.

```java
@Transactional
public ReconciliationLog reconcileForDate(LocalDate date) {
    List<Transaction> localTopups = transactionRepository.findTopupsCreatedBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    Map<String, Transaction> localByPaymentId = /* index those with a payment id */;
    List<GatewayPayment> gatewayPayments = gatewaySource.fetchPaymentsForDate(date);   // interface!
    Map<String, GatewayPayment> gatewayById = /* index by payment id */;

    List<ReconciliationMismatch> mismatches = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    // Direction 1: every gateway payment
    for (GatewayPayment gp : gatewayPayments) {
        seen.add(gp.gatewayPaymentId());
        Transaction local = localByPaymentId.get(gp.gatewayPaymentId());
        if (local == null) { mismatches.add(MISSING_LOCALLY); continue; }
        boolean localOk = local.getStatus() == SUCCESS;
        boolean gwOk    = gp.status() == CAPTURED;
        if (localOk != gwOk) mismatches.add(STATUS_MISMATCH);
        else if (gwOk && local.getAmount().compareTo(gp.amount()) != 0) mismatches.add(AMOUNT_MISMATCH);
    }
    // Direction 2: local SUCCESS top-ups the gateway didn't return
    for (Transaction local : localTopups)
        if (local.getStatus() == SUCCESS && hasPaymentId(local) && !seen.contains(local.getRazorpayPaymentId()))
            mismatches.add(MISSING_AT_GATEWAY);

    return reconciliationLogRepository.save(ReconciliationLog.builder()
            .runDate(date).totalChecked(localTopups.size() + gatewayPayments.size())
            .mismatchesFound(mismatches.size()).mismatchDetailsJson(toJson(mismatches))
            .status("COMPLETED").build());
}
```

The **gateway abstraction** (the strong point):

```java
public interface PaymentGatewayReconciliationSource {
    List<GatewayPayment> fetchPaymentsForDate(LocalDate date);
    String providerName();
}
// SimulatedGatewaySource  (@ConditionalOnProperty ... havingValue="simulated", matchIfMissing=true)
// RazorpayGatewaySource   (@ConditionalOnProperty ... havingValue="razorpay")
```

**Talk about:** Join key = gateway payment id. **Two-directional (full outer)** walk catches drift on either side. Only TOPUPs (only they touch the gateway). **Read-only** — detects, never auto-fixes (auto-correcting money from a batch job is dangerous). Engine depends only on the **interface** → swap simulated↔real via one config property, zero engine changes, fully testable without Razorpay.

---

## 11. Correlation-ID logging filter

**What:** Tag every log line of one request with a shared id for tracing.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)               // runs before all other filters
public class CorrelationIdFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);            // per-thread log context
        res.setHeader("X-Request-Id", requestId);
        try { chain.doFilter(req, res); }
        finally { MDC.remove("requestId"); }        // CRITICAL: threads are pooled/reused
    }
}
```

Log pattern: `... %X{requestId} ... : %m%n`

**Talk about:** MDC is thread-bound; the log pattern prints `%X{requestId}` on every line, so controller→service→repo logs share one id. **Must clear in `finally`** or the next request on that pooled thread inherits the old id. Honors an inbound header so the id can span services.

---

## 12. RBAC + admin seeding

**What:** One `role` column; `/api/admin/**` gated by role; first admin seeded from env (never self-registerable).

```java
// UserPrincipal — map role to a Spring authority
public Collection<? extends GrantedAuthority> getAuthorities() {
    return AuthorityUtils.createAuthorityList("ROLE_" + user.getRole().name());
}

// SecurityConfig — the gate
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/auth/**", "/api/webhooks/**", "/actuator/health").permitAll()
    .requestMatchers("/api/admin/**").hasRole("ADMIN")     // anonymous→401, non-admin→403
    .anyRequest().authenticated())

// AdminSeeder — first admin from env vars, idempotent
@Override @Transactional
public void run(String... args) {
    if (adminEmail.isBlank() || adminPassword.isBlank()) return;         // safe default: no admin
    if (userRepository.existsByEmail(adminEmail)) return;                 // idempotent
    User admin = userRepository.save(User.builder().name(adminName).email(adminEmail)
            .passwordHash(passwordEncoder.encode(adminPassword)).role(Role.ADMIN).build());
    walletRepository.save(Wallet.builder().user(admin).balance(BigDecimal.ZERO).build());
}
```

**Talk about:** No "register as admin" endpoint (that's a privilege-escalation hole). First admin comes from trusted server config (env vars), created by a `CommandLineRunner` on startup. `hasRole("ADMIN")` auto-prepends `ROLE_`, so authorities must use that prefix. _(Bug story: `ddl-auto=update` didn't backfill the new `role` column on existing rows → old users defaulted wrong → fixed data + added DB-level `DEFAULT 'USER'`.)_

---

## 13. Axios token refresh (frontend)

**What:** Transparently refresh an expired access token and retry the request, so the user stays logged in.

```javascript
api.interceptors.response.use(
  (r) => r,
  async (error) => {
    const original = error.config;
    if (
      error.response?.status === 401 &&
      !original._retry &&
      !original.url?.includes("/auth/")
    ) {
      original._retry = true; // guard against infinite loop
      const refreshToken = localStorage.getItem("refreshToken");
      if (!refreshToken) return Promise.reject(error);
      try {
        if (!refreshInFlight) {
          // share ONE refresh across concurrent 401s
          refreshInFlight = axios
            .post("/api/auth/refresh", { refreshToken })
            .finally(() => {
              refreshInFlight = null;
            });
        }
        const { data } = await refreshInFlight;
        localStorage.setItem("accessToken", data.accessToken);
        localStorage.setItem("refreshToken", data.refreshToken);
        original.headers.Authorization = `Bearer ${data.accessToken}`;
        return api(original); // retry the original request
      } catch (e) {
        localStorage.clear();
        window.location.href = "/login"; // refresh failed → force logout
        return Promise.reject(e);
      }
    }
    return Promise.reject(error);
  },
);
```

**Talk about:** On 401, do **one** silent refresh (shared in-flight promise so many concurrent 401s don't each fire a refresh), retry the original request. `_retry` flag + refresh failure → hard logout prevents loops. This is what makes 15-min access tokens invisible to the user.

---

## Quick "which class does what" map

| Concern        | Class                                                                                                                       |
| -------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Auth logic     | `AuthService`, `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`                                                    |
| Money movement | `TransferService`, `TopUpService`, `LedgerEntry`, `TransactionStateMachine`                                                 |
| Webhooks       | `WebhookController`, `WebhookSignatureVerifier`, `WebhookService`                                                           |
| Reconciliation | `ReconciliationService`, `PaymentGatewayReconciliationSource`, `Simulated/RazorpayGatewaySource`, `ReconciliationScheduler` |
| Admin/RBAC     | `AdminController`, `AdminService`, `AdminSeeder`, `Role`, `SecurityConfig`                                                  |
| Cross-cutting  | `CorrelationIdFilter`, `GlobalExceptionHandler`                                                                             |

---

_Pair this with `INTERVIEW_PREP.md` (concepts & trade-offs) and `INTERVIEW_CHEATSHEET.md` (1-page recall)._
