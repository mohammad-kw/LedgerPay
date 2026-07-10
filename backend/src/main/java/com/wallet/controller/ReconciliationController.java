package com.wallet.controller;

import com.wallet.dto.ReconciliationLogResponse;
import com.wallet.entity.ReconciliationLog;
import com.wallet.reconciliation.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin endpoints for the reconciliation job (PROJECT_SPEC.md Section 3.5):
 *
 *   POST /api/admin/reconciliation/run   - manually trigger a run
 *   GET  /api/admin/reconciliation/logs  - list past runs (newest first)
 *
 * Both live under /api/admin/** which, per SecurityConfig, still requires a
 * valid JWT (it is NOT in the permit-all list). In a fuller build this would
 * be further restricted to an ADMIN role; for this project it is an
 * authenticated-only operational endpoint, which is documented as a known
 * simplification.
 */
@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
@Slf4j
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    /**
     * Manually trigger reconciliation for a single day.
     *
     * @param date optional ?date=YYYY-MM-DD; defaults to YESTERDAY, because a
     *             given day's payments are only fully settled once the day is
     *             over - reconciling "today" mid-day would flag still-in-flight
     *             payments as false mismatches. The daily @Scheduled job uses
     *             the same default.
     */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationLogResponse> run(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = (date != null) ? date : LocalDate.now().minusDays(1);
        log.info("Manual reconciliation triggered for {}", target);
        ReconciliationLog result = reconciliationService.reconcileForDate(target);
        return ResponseEntity.ok(ReconciliationLogResponse.from(result));
    }

    /** List every past reconciliation run, newest first. */
    @GetMapping("/logs")
    public ResponseEntity<List<ReconciliationLogResponse>> logs() {
        List<ReconciliationLogResponse> body = reconciliationService.getAllLogs().stream()
                .map(ReconciliationLogResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
