package com.wallet.dto;

import com.wallet.entity.ReconciliationLog;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API view of a single {@link ReconciliationLog} row, returned by
 * POST /api/admin/reconciliation/run and GET /api/admin/reconciliation/logs.
 *
 * We expose a DTO instead of the JPA entity so the wire contract is decoupled
 * from the persistence model (a general good practice), and so the raw
 * {@code mismatchDetailsJson} - already a JSON string - is passed through
 * untouched rather than being double-encoded by Jackson.
 */
public record ReconciliationLogResponse(
        Long id,
        LocalDate runDate,
        Integer totalChecked,
        Integer mismatchesFound,
        String mismatchDetailsJson,
        String status,
        LocalDateTime createdAt) {

    public static ReconciliationLogResponse from(ReconciliationLog log) {
        return new ReconciliationLogResponse(
                log.getId(),
                log.getRunDate(),
                log.getTotalChecked(),
                log.getMismatchesFound(),
                log.getMismatchDetailsJson(),
                log.getStatus(),
                log.getCreatedAt());
    }
}
