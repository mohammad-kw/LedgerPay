package com.wallet.repository;

import com.wallet.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LedgerEntry}. See UserRepository's
 * javadoc for how these interfaces work.
 *
 * Ledger entries are the append-only, double-entry source of truth for money
 * movement (PROJECT_SPEC.md Section 3.2). For now we only ever INSERT them
 * (via the inherited save()), so this interface adds no custom finders yet -
 * read/statement queries can be added in a later phase when we build a
 * transaction-detail or statement view. It exists as its own repository
 * (rather than reusing another) to keep the ledger a clearly separate,
 * first-class concept in the codebase.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
}
