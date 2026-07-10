package com.wallet.webhook;

import com.wallet.config.RazorpayProperties;
import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.repository.WebhookEventRepository;
import com.wallet.webhook.WebhookService.WebhookResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the FULL top-up confirmation flow (PROJECT_SPEC.md
 * Phase 4: "full top-up flow (initiate -> simulated webhook -> balance
 * updated)" and "duplicate webhook event ignored").
 *
 * Unlike the unit tests, this loads a real Spring context and a real (H2)
 * database (see src/test/resources/application.properties), and drives the
 * ACTUAL WebhookService against real repositories - so it proves the pieces
 * (signature verification, event dedupe, state machine, ledger write, cached
 * balance update, all inside one @Transactional) work together end-to-end.
 *
 * We simulate Razorpay by building a webhook JSON body and signing it with the
 * SAME test webhook secret the app is configured with (autowired via
 * RazorpayProperties), so signature verification passes for real - no mocking
 * of the crypto.
 */
@SpringBootTest
class WebhookFlowIntegrationTest {

    @Autowired private WebhookService webhookService;
    @Autowired private RazorpayProperties razorpayProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        // Clean slate each test (create-drop rebuilds schema per context, but
        // we delete to be independent of test ordering within a context).
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .name("Topup User").email("topup@test.com").passwordHash("x").build());
        wallet = walletRepository.save(Wallet.builder()
                .user(user).balance(new BigDecimal("0.00")).currency("INR").build());
    }

    /** Seed a CREATED TOPUP transaction awaiting webhook confirmation, as TopUpService would after creating a Razorpay order. */
    private Transaction seedCreatedTopUp(String orderId, BigDecimal amount) {
        return transactionRepository.save(Transaction.builder()
                .idempotencyKey("idem-" + orderId)
                .type(TransactionType.TOPUP)
                .status(TransactionStatus.CREATED)
                .receiverWallet(wallet)
                .amount(amount)
                .currency("INR")
                .razorpayOrderId(orderId)
                .build());
    }

    /** Build a Razorpay-style payment.captured webhook body for a given order/payment. */
    private String capturedPayload(String orderId, String paymentId) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\"}}}}";
    }

    private String failedPayload(String orderId, String paymentId) {
        return "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\"}}}}";
    }

    /** Sign a body exactly as Razorpay would, using the app's configured webhook secret. */
    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    razorpayProperties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Happy path: captured webhook credits the wallet -------------------

    @Test
    @DisplayName("payment.captured webhook promotes the txn to SUCCESS, writes a CREDIT ledger entry, and updates the cached balance")
    void capturedWebhook_creditsWalletEndToEnd() {
        Transaction txn = seedCreatedTopUp("order_A", new BigDecimal("500.00"));
        String body = capturedPayload("order_A", "pay_A");

        WebhookResult result = webhookService.process(body, sign(body));

        assertThat(result).isEqualTo(WebhookResult.PROCESSED);

        // Transaction promoted to SUCCESS with the payment id recorded.
        Transaction updated = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(updated.getRazorpayPaymentId()).isEqualTo("pay_A");

        // Cached balance updated.
        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("500.00");

        // Exactly one CREDIT ledger entry, matching the amount and new balance.
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        LedgerEntry credit = entries.get(0);
        assertThat(credit.getEntryType()).isEqualTo(EntryType.CREDIT);
        assertThat(credit.getAmount()).isEqualByComparingTo("500.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("500.00");

        // The raw event was stored and marked processed (audit trail).
        assertThat(webhookEventRepository.count()).isEqualTo(1);
    }

    // ---- Duplicate delivery: same event must not double-credit -------------

    @Test
    @DisplayName("a duplicate captured webhook (same event) is ignored and does NOT credit the wallet twice")
    void duplicateWebhook_isIgnored() {
        seedCreatedTopUp("order_B", new BigDecimal("300.00"));
        String body = capturedPayload("order_B", "pay_B");
        String signature = sign(body);

        WebhookResult first = webhookService.process(body, signature);
        WebhookResult second = webhookService.process(body, signature); // exact same event again

        assertThat(first).isEqualTo(WebhookResult.PROCESSED);
        assertThat(second).isEqualTo(WebhookResult.DUPLICATE);

        // Balance credited once only.
        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("300.00");
        // Only one ledger entry and one stored event despite two deliveries.
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
        assertThat(webhookEventRepository.count()).isEqualTo(1);
    }

    // ---- Bad signature: nothing happens ------------------------------------

    @Test
    @DisplayName("an invalid signature is rejected: no credit, no ledger entry, no stored event")
    void invalidSignature_isRejectedAndChangesNothing() {
        seedCreatedTopUp("order_C", new BigDecimal("250.00"));
        String body = capturedPayload("order_C", "pay_C");

        WebhookResult result = webhookService.process(body, "deadbeef_not_a_real_signature");

        assertThat(result).isEqualTo(WebhookResult.INVALID_SIGNATURE);
        // Absolutely nothing changed.
        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(webhookEventRepository.count()).isZero();
    }

    /*
     * A test case you might not think of yourself: payment.FAILED must move the
     * transaction to FAILED but write NO ledger entry and leave the balance
     * untouched. It's easy to only test the happy "captured" path and forget
     * that the failure path must be equally strict about NOT moving money.
     */
    @Test
    @DisplayName("payment.failed marks the txn FAILED and writes no ledger entry / no balance change")
    void failedWebhook_marksFailedWithoutMovingMoney() {
        Transaction txn = seedCreatedTopUp("order_D", new BigDecimal("400.00"));
        String body = failedPayload("order_D", "pay_D");

        WebhookResult result = webhookService.process(body, sign(body));

        assertThat(result).isEqualTo(WebhookResult.PROCESSED);
        Transaction updated = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.FAILED);

        Wallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(ledgerEntryRepository.count()).isZero();
    }
}
