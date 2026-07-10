package com.wallet.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The system-wide metrics shown at the top of the admin dashboard
 * (GET /api/admin/metrics). Purpose-built read-only reporting shape - it is
 * NOT any entity, and exposes only aggregate numbers, never individual user
 * data.
 *
 * The two Map fields feed the dashboard charts directly:
 *   - transactionsByStatus: e.g. {"SUCCESS": 12, "FAILED": 1, ...} -> a
 *     doughnut/pie chart of transaction health.
 *   - transactionsByType:   e.g. {"TOPUP": 8, "TRANSFER": 5, ...} -> a bar
 *     chart of what kind of activity the system sees.
 */
public record AdminMetricsResponse(
        long totalUsers,
        long totalWallets,
        BigDecimal totalBalanceHeld,
        long transactionsToday,
        BigDecimal topupVolumeToday,
        BigDecimal transferVolumeToday,
        Map<String, Long> transactionsByStatus,
        Map<String, Long> transactionsByType) {
}
