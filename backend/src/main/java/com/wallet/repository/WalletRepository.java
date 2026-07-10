package com.wallet.repository;

import com.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Wallet}. See UserRepository's
 * javadoc for a general explanation of how Spring Data JPA repository
 * interfaces work (we declare the interface, Spring generates the
 * implementation).
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Look up a user's wallet by their user id. Recall from Wallet.java
     * that the User<->Wallet relationship is deliberately unidirectional
     * (Wallet -> User only, no back-reference on User) - this repository
     * method is the intended way to go "the other direction" (given a
     * user, find their wallet) without needing that back-reference.
     *
     * Spring Data parses "findByUserId" as: navigate to this entity's
     * `user` field, then that field's `id` property, and filter on it -
     * equivalent to JPQL "SELECT w FROM Wallet w WHERE w.user.id = :userId".
     */
    Optional<Wallet> findByUserId(Long userId);

    /**
     * The total amount of money currently held across EVERY wallet in the
     * system (the "float"), for the admin dashboard. COALESCE returns 0
     * instead of null when there are no wallets yet. Admin-only aggregate.
     */
    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM Wallet w")
    BigDecimal sumAllBalances();
}