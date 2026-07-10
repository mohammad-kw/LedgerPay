package com.wallet.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.entity.ReconciliationLog;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.reconciliation.GatewayPayment.GatewayPaymentStatus;
import com.wallet.repository.ReconciliationLogRepository;
import com.wallet.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the reconciliation ENGINE ({@link ReconciliationService})
 * - the project's key differentiator (PROJECT_SPEC.md Section 3.5).
 *
 * The gateway source and both repositories are Mockito mocks, so we can feed
 * the engine an EXACT local-vs-gateway scenario and assert precisely which
 * mismatches it detects. Each test isolates one of the four MismatchType cases,
 * plus a clean (zero-mismatch) run, plus the two-direction "full outer join"
 * behaviour.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private ReconciliationLogRepository reconciliationLogRepository;
    @Mock private PaymentGatewayReconciliationSource gatewaySource;

    private ReconciliationService service;

    private final LocalDate day = LocalDate.of(2026, 7, 8);

    @BeforeEach
    void setUp() {
        // Real ObjectMapper (not mocked) so the JSON serialization path is
        // exercised for real.
        service = new ReconciliationService(
                transactionRepository, reconciliationLogRepository,
                gatewaySource, new ObjectMapper());

        // The log repo just echoes back whatever it's asked to save.
        when(reconciliationLogRepository.save(any(ReconciliationLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Build a SUCCESS top-up transaction with a gateway payment id. */
    private Transaction topup(long id, String paymentId, BigDecimal amount, TransactionStatus status) {
        return Transaction.builder()
                .id(id)
                .type(TransactionType.TOPUP)
                .status(status)
                .amount(amount)
                .currency("INR")
                .razorpayPaymentId(paymentId)
                .createdAt(day.atTime(10, 0))
                .build();
    }

    private GatewayPayment gw(String paymentId, GatewayPaymentStatus status, BigDecimal amount) {
        return new GatewayPayment(paymentId, status, amount, "INR");
    }

    private void givenLocal(Transaction... txns) {
        when(transactionRepository.findTopupsCreatedBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(txns));
    }

    private void givenGateway(GatewayPayment... payments) {
        when(gatewaySource.fetchPaymentsForDate(eq(day))).thenReturn(List.of(payments));
    }

    private ReconciliationLog run() {
        return service.reconcileForDate(day);
    }

    @Test
    @DisplayName("Clean run: local SUCCESS matches gateway CAPTURED, same amount -> 0 mismatches")
    void cleanRun() {
        givenLocal(topup(1, "pay_1", new BigDecimal("500.00"), TransactionStatus.SUCCESS));
        givenGateway(gw("pay_1", GatewayPaymentStatus.CAPTURED, new BigDecimal("500.00")));

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isZero();
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getTotalChecked()).isEqualTo(2); // 1 local + 1 gateway
    }

    @Test
    @DisplayName("MISSING_LOCALLY: gateway has a payment we never recorded")
    void missingLocally() {
        givenLocal(); // no local top-ups
        givenGateway(gw("pay_ghost", GatewayPaymentStatus.CAPTURED, new BigDecimal("999.00")));

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isEqualTo(1);
        assertThat(result.getMismatchDetailsJson()).contains("MISSING_LOCALLY", "pay_ghost");
    }

    @Test
    @DisplayName("MISSING_AT_GATEWAY: local SUCCESS top-up the gateway didn't return")
    void missingAtGateway() {
        givenLocal(topup(2, "pay_2", new BigDecimal("300.00"), TransactionStatus.SUCCESS));
        givenGateway(); // gateway returns nothing

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isEqualTo(1);
        assertThat(result.getMismatchDetailsJson()).contains("MISSING_AT_GATEWAY", "pay_2");
    }

    @Test
    @DisplayName("STATUS_MISMATCH: local says SUCCESS but gateway says FAILED")
    void statusMismatch() {
        givenLocal(topup(3, "pay_3", new BigDecimal("250.00"), TransactionStatus.SUCCESS));
        givenGateway(gw("pay_3", GatewayPaymentStatus.FAILED, new BigDecimal("250.00")));

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isEqualTo(1);
        assertThat(result.getMismatchDetailsJson()).contains("STATUS_MISMATCH", "pay_3");
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH: both agree it succeeded but amounts differ")
    void amountMismatch() {
        givenLocal(topup(4, "pay_4", new BigDecimal("500.00"), TransactionStatus.SUCCESS));
        givenGateway(gw("pay_4", GatewayPaymentStatus.CAPTURED, new BigDecimal("501.00")));

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isEqualTo(1);
        assertThat(result.getMismatchDetailsJson()).contains("AMOUNT_MISMATCH", "pay_4");
    }

    @Test
    @DisplayName("Full-outer walk: detects mismatches in BOTH directions in one run")
    void bothDirections() {
        givenLocal(
                topup(5, "pay_ok", new BigDecimal("100.00"), TransactionStatus.SUCCESS),   // clean
                topup(6, "pay_local_only", new BigDecimal("200.00"), TransactionStatus.SUCCESS) // MISSING_AT_GATEWAY
        );
        givenGateway(
                gw("pay_ok", GatewayPaymentStatus.CAPTURED, new BigDecimal("100.00")),      // clean
                gw("pay_gw_only", GatewayPaymentStatus.CAPTURED, new BigDecimal("50.00"))   // MISSING_LOCALLY
        );

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isEqualTo(2);
        assertThat(result.getMismatchDetailsJson())
                .contains("MISSING_AT_GATEWAY", "pay_local_only")
                .contains("MISSING_LOCALLY", "pay_gw_only");
    }

    @Test
    @DisplayName("In-flight local top-ups (CREATED, no payment id) are ignored, not flagged")
    void inFlightIgnored() {
        givenLocal(topup(7, null, new BigDecimal("400.00"), TransactionStatus.CREATED));
        givenGateway();

        ReconciliationLog result = run();

        assertThat(result.getMismatchesFound()).isZero();
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Gateway failure -> run recorded as FAILED, not lost")
    void gatewayFailureRecordedAsFailed() {
        givenLocal(topup(8, "pay_8", new BigDecimal("100.00"), TransactionStatus.SUCCESS));
        when(gatewaySource.fetchPaymentsForDate(eq(day)))
                .thenThrow(new IllegalStateException("gateway down"));

        ReconciliationLog result = run();

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getMismatchDetailsJson()).contains("gateway down");
    }

    @Test
    @DisplayName("The persisted log summarises totals correctly")
    void persistsSummary() {
        givenLocal(topup(9, "pay_9", new BigDecimal("500.00"), TransactionStatus.SUCCESS));
        givenGateway(gw("pay_9", GatewayPaymentStatus.FAILED, new BigDecimal("500.00")));

        run();

        ArgumentCaptor<ReconciliationLog> captor = ArgumentCaptor.forClass(ReconciliationLog.class);
        org.mockito.Mockito.verify(reconciliationLogRepository).save(captor.capture());
        ReconciliationLog saved = captor.getValue();
        assertThat(saved.getRunDate()).isEqualTo(day);
        assertThat(saved.getTotalChecked()).isEqualTo(2);
        assertThat(saved.getMismatchesFound()).isEqualTo(1);
    }
}
