package com.wallet.dto;

import java.util.List;

/**
 * The full admin detail view of one user (GET /api/admin/users/{id}):
 * their profile + wallet summary, plus their complete transaction history.
 *
 * Like AdminUserResponse, this has NO password field - it reuses the flat
 * AdminUserResponse for the profile block and the existing
 * AdminTransactionResponse rows for the history, so no sensitive data can
 * leak through this shape.
 */
public record AdminUserDetailResponse(
        AdminUserResponse user,
        List<AdminTransactionResponse> transactions) {
}
