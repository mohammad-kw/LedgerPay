package com.wallet.reconciliation;

import com.wallet.entity.ReconciliationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs the reconciliation job automatically once per day (PROJECT_SPEC.md
 * Section 3.5: "Also add a @Scheduled job to run this once daily").
 *
 * ── Why 02:00 on YESTERDAY ───────────────────────────────────────────────
 * We reconcile the PREVIOUS calendar day, not the current one, because a
 * day's payments are only fully settled once that day is over. Running in the
 * small hours (02:00) reconciles a day that has fully closed, avoiding
 * false-positive "mismatches" from payments still in flight. This is the same
 * default the manual POST /run endpoint uses when no ?date is given.
 *
 * The cron is driven by a property so it can be tuned per-environment without
 * a recompile (and disabled in tests by simply never scheduling in that
 * profile). The zone is pinned to Asia/Kolkata so the job fires at 02:00 IST
 * regardless of the server's OS timezone.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    /**
     * Fires daily at 02:00 IST. Cron format is
     * {@code second minute hour day-of-month month day-of-week}.
     */
    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void runDaily() {
        LocalDate target = LocalDate.now().minusDays(1);
        log.info("Scheduled reconciliation starting for {}", target);
        ReconciliationLog result = reconciliationService.reconcileForDate(target);
        log.info("Scheduled reconciliation for {} finished: status={} mismatches={}",
                target, result.getStatus(), result.getMismatchesFound());
    }
}
