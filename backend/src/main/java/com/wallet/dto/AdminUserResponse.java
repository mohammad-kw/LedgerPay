package com.wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row in the admin "all users" list (GET /api/admin/users).
 *
 * A flat, purpose-built projection for oversight. Crucially it has NO
 * password field at all - the same "a DTO can't leak what it doesn't have"
 * principle used across this codebase makes it structurally impossible to
 * expose the BCrypt hash, even by accident.
 *
 * walletBalance and transactionCount are computed per user in AdminService so
 * the admin can scan the list without opening each user individually.
 */
public record AdminUserResponse(
        Long id,
        String name,
        String email,
        String phone,
        String role,
        BigDecimal walletBalance,
        long transactionCount,
        LocalDateTime createdAt) {
}
