package com.wallet.dto;

import java.math.BigDecimal;

/**
 * The JSON response body for GET /api/wallet/balance (PROJECT_SPEC.md
 * Section 5). See RegisterResponse's javadoc for why this is a plain Java
 * `record`.
 *
 * This is a deliberately tiny, purpose-built view of a {@link
 * com.wallet.entity.Wallet} - it exposes only the three things a client
 * needs to render a balance ("how much", "in what currency", "which
 * wallet"), and nothing else. In particular it does NOT expose the owning
 * User, the internal ledger rows, or the created/updated timestamps. This
 * "never return entities directly from controllers, map them to a DTO" rule
 * keeps our internal database shape decoupled from our public API contract,
 * so we can refactor entities later without breaking clients.
 *
 * `balance` is a BigDecimal (never a double) for the same exact-decimal-
 * money reason explained on Wallet.balance - see that field's javadoc.
 */
public record BalanceResponse(

        /** Which wallet this balance is for - handy for the frontend and for debugging, though a user only ever has one wallet in this phase. */
        Long walletId,

        /** The current cached balance (see Wallet.balance - it's derived from the ledger, and is 0.00 for a brand-new account until top-ups exist). */
        BigDecimal balance,

        /** ISO currency code, e.g. "INR" (see Wallet.currency). */
        String currency
) {
}
