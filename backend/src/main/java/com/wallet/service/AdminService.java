package com.wallet.service;

import com.wallet.dto.AdminMetricsResponse;
import com.wallet.dto.AdminTransactionResponse;
import com.wallet.dto.AdminUserDetailResponse;
import com.wallet.dto.AdminUserResponse;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.exception.UserNotFoundException;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only reporting logic behind the admin dashboard (the /api/admin
 * endpoints). Every method here is a SELECT-only aggregation - the admin
 * views observe the system, they never mutate money or state (the one
 * exception, triggering a reconciliation run, lives in its own
 * ReconciliationController/Service and only writes an audit log).
 *
 * All methods are @Transactional(readOnly = true): it keeps the Hibernate
 * session open long enough to read LAZY associations (e.g. a transaction's
 * sender/receiver wallet ids) while mapping to DTOs, and readOnly lets the DB/
 * Hibernate skip dirty-checking for a small performance win on pure reads.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /** Aggregate counts/sums for the dashboard header cards and charts. */
    @Transactional(readOnly = true)
    public AdminMetricsResponse getMetrics() {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        // Per-status and per-type counts, in a stable, chart-friendly order
        // (LinkedHashMap preserves the enum declaration order).
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TransactionStatus s : TransactionStatus.values()) {
            byStatus.put(s.name(), transactionRepository.countByStatus(s));
        }
        Map<String, Long> byType = new LinkedHashMap<>();
        for (TransactionType t : TransactionType.values()) {
            byType.put(t.name(), transactionRepository.countByType(t));
        }

        BigDecimal topupToday = transactionRepository
                .sumSuccessfulAmountByTypeSince(TransactionType.TOPUP, startOfToday);
        BigDecimal transferToday = transactionRepository
                .sumSuccessfulAmountByTypeSince(TransactionType.TRANSFER, startOfToday);

        return new AdminMetricsResponse(
                userRepository.count(),
                walletRepository.count(),
                walletRepository.sumAllBalances(),
                transactionRepository.countByCreatedAtAfter(startOfToday),
                topupToday,
                transferToday,
                byStatus,
                byType);
    }

    /**
     * The most recent transactions across ALL users, newest first, for the
     * admin activity feed.
     *
     * @param limit how many rows to return (clamped to a sane 1..200 range so
     *              an admin can't accidentally ask for the entire table).
     */
    @Transactional(readOnly = true)
    public List<AdminTransactionResponse> getRecentTransactions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return transactionRepository
                .findRecentAcrossAllUsers(PageRequest.of(0, safeLimit))
                .stream()
                .map(AdminTransactionResponse::from)
                .toList();
    }

    /**
     * Every user in the system (newest first), each with their wallet balance
     * and transaction count, for the admin "all users" list. Never exposes
     * password hashes - AdminUserResponse has no such field.
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .map(this::toUserResponse)
                .toList();
    }

    /**
     * One user's profile + wallet + full transaction history, for the admin
     * user-detail view. Throws UserNotFoundException (-> 404) if the id is
     * unknown.
     */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUserDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + userId));

        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);

        List<AdminTransactionResponse> transactions = wallet == null
                ? List.of()
                : transactionRepository.findAllForWallet(wallet.getId())
                        .stream()
                        .map(AdminTransactionResponse::from)
                        .toList();

        return new AdminUserDetailResponse(toUserResponse(user), transactions);
    }

    /** Map a User (+ its wallet) into the password-free admin projection. */
    private AdminUserResponse toUserResponse(User user) {
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElse(null);
        BigDecimal balance = wallet != null ? wallet.getBalance() : BigDecimal.ZERO;
        long txnCount = wallet != null ? transactionRepository.countForWallet(wallet.getId()) : 0L;

        return new AdminUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().name(),
                balance,
                txnCount,
                user.getCreatedAt());
    }
}
