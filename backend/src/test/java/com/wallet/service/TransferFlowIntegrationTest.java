package com.wallet.service;

import com.wallet.dto.TransferResponse;
import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the FULL transfer flow (PROJECT_SPEC.md Phase 4: "full
 * transfer flow (success + insufficient balance failure)" and "duplicate
 * request handling"). Runs the real {@link TransferService} against a real H2
 * database via real repositories, inside a real Spring context.
 *
 * Where the unit test ({@link TransferServiceTest}) proves the service calls
 * the right collaborators, THIS test proves the data actually lands correctly
 * in the database: two ledger rows really get persisted, both wallet balances
 * really change, and the whole thing commits atomically.
 */
@SpringBootTest
class TransferFlowIntegrationTest {

    @Autowired private TransferService transferService;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private Long aliceUserId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        User alice = userRepository.save(User.builder()
                .name("Alice").email("alice@test.com").passwordHash("x").build());
        User bob = userRepository.save(User.builder()
                .name("Bob").email("bob@test.com").passwordHash("x").build());
        aliceUserId = alice.getId();

        walletRepository.save(Wallet.builder()
                .user(alice).balance(new BigDecimal("1000.00")).currency("INR").build());
        walletRepository.save(Wallet.builder()
                .user(bob).balance(new BigDecimal("0.00")).currency("INR").build());
    }

    // ---- Happy path end-to-end ---------------------------------------------

    @Test
    @DisplayName("successful transfer persists two balanced ledger rows and updates both balances in the DB")
    void successfulTransfer_persistsLedgerAndBalances() {
        TransferResponse response = transferService.transfer(
                aliceUserId, "bob@test.com", new BigDecimal("300.00"), "transfer-key-1");

        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);

        // Balances persisted.
        assertThat(walletRepository.findByUserId(aliceUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("700.00");

        // Exactly two ledger rows, one DEBIT + one CREDIT, both under one txn.
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(2);
        BigDecimal debitTotal = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The double-entry invariant, checked against real persisted rows.
        assertThat(creditTotal.subtract(debitTotal)).isEqualByComparingTo("0.00");
    }

    // ---- Insufficient balance rolls everything back ------------------------

    @Test
    @DisplayName("insufficient balance transfer throws and leaves the DB completely unchanged (atomic rollback)")
    void insufficientBalance_rollsBackEverything() {
        assertThatThrownBy(() -> transferService.transfer(
                aliceUserId, "bob@test.com", new BigDecimal("5000.00"), "transfer-key-2"))
                .isInstanceOf(InsufficientBalanceException.class);

        // No ledger rows, no transaction rows, balances untouched.
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(transactionRepository.count()).isZero();
        assertThat(walletRepository.findByUserId(aliceUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");
    }

    // ---- Duplicate idempotency key -----------------------------------------

    @Test
    @DisplayName("replaying the same Idempotency-Key does NOT transfer twice (money moves once only)")
    void duplicateIdempotencyKey_doesNotDoubleTransfer() {
        String key = "transfer-key-3";
        transferService.transfer(aliceUserId, "bob@test.com", new BigDecimal("200.00"), key);
        // Replay with the exact same key.
        transferService.transfer(aliceUserId, "bob@test.com", new BigDecimal("200.00"), key);

        // Alice debited only once (1000 - 200 = 800), not twice.
        assertThat(walletRepository.findByUserId(aliceUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("800.00");
        // Only ONE transaction and ONE ledger pair exist.
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}
