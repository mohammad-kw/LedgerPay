package com.wallet.repository;

import com.wallet.entity.ReconciliationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link ReconciliationLog}. See
 * UserRepository's javadoc for how these interfaces work.
 *
 * Backs the reconciliation audit trail (PROJECT_SPEC.md Section 3.5 & 5):
 * every run of the job writes one row here, and GET
 * /api/admin/reconciliation/logs reads them back, newest first.
 */
public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {

    /**
     * All reconciliation runs, most recent first, for the "view past runs"
     * admin endpoint. Ordered by id DESC (id is monotonically increasing, so
     * newest rows have the highest id) which is a stable ordering even when
     * multiple runs happen on the same run_date/created_at.
     */
    List<ReconciliationLog> findAllByOrderByIdDesc();
}
