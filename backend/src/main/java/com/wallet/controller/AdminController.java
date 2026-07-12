package com.wallet.controller;

import com.wallet.dto.AdminMetricsResponse;
import com.wallet.dto.AdminTransactionResponse;
import com.wallet.dto.AdminUserDetailResponse;
import com.wallet.dto.AdminUserResponse;
import com.wallet.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only admin dashboard endpoints:
 *   GET /api/admin/metrics       - system-wide counts/sums + chart data
 *   GET /api/admin/transactions  - global recent-transaction feed
 *
 * These sit under /api/admin/** which SecurityConfig locks to hasRole("ADMIN"),
 * so an ordinary logged-in user gets 403 and an anonymous caller gets 401 -
 * the controller itself never has to check the role, that's enforced by the
 * filter chain before any method here runs. Reconciliation (POST /run,
 * GET /logs) lives in its own ReconciliationController under the same prefix.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    /** GET /api/admin/metrics - aggregate numbers for the dashboard cards and charts. */
    @GetMapping("/metrics")
    public ResponseEntity<AdminMetricsResponse> metrics() {
        return ResponseEntity.ok(adminService.getMetrics());
    }

    /**
     * GET /api/admin/transactions?limit=50 - the global activity feed
     * across all users, newest first. `limit` defaults to 50 and is clamped
     * server-side to 1..200.
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<AdminTransactionResponse>> recentTransactions(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(adminService.getRecentTransactions(limit));
    }

    /** GET /api/admin/users - every user with wallet balance + transaction count (no passwords). */
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> users() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    /** GET /api/admin/users/{id} - one user's profile + full transaction history. 404 if unknown. */
    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserDetailResponse> userDetail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(adminService.getUserDetail(id));
    }
}
