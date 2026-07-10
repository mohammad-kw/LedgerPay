package com.wallet.service;

import com.wallet.dto.TransferResponse;
import com.wallet.entity.EntryType;
import com.wallet.entity.LedgerEntry;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.exception.InsufficientBalanceException;
import com.wallet.exception.InvalidTransferException;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link TransferService} - the correctness core of the
 * whole project (PROJECT_SPEC.md Section 3.1/3.2 + Phase 4 unit-test
 * requirements: idempotency dedupe, ledger nets to zero, failure paths).
 *
 * These are UNIT tests: every collaborator (the repositories) is a Mockito
 * mock, so there's no database and no Spring context. That keeps them fast and
 * lets us assert precisely WHICH repository calls happened (or didn't) - which
 * is exactly how we prove "a duplicate request does NOT write a second
 * transaction" and "a rejected transfer writes NOTHING".
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks private TransferService transferService;

    private static final Long SENDER_USER_ID = 1L;
    private static final String RECEIVER_EMAIL = "receiver@test.com";
    private static final String IDEMPOTENCY_KEY = "key-123";

    private Wallet senderWallet;
    private Wallet receiverWallet;
    private User receiver;

    @BeforeEach
    void setUp() {
        senderWallet = Wallet.builder().id(10L).balance(new BigDecimal("1000.00")).currency("INR").build();
        receiverWallet = Wallet.builder().id(20L).balance(new BigDecimal("50.00")).currency("INR").build();
        receiver = User.builder().id(2L).email(RECEIVER_EMAIL).build();
    }

    // ---- Happy path: ledger + balances + double-entry invariant ------------

    @Test
    @DisplayName("successful transfer writes a balanced DEBIT+CREDIT pair that nets to zero and updates both balances")
    void successfulTransfer_writesBalancedLedgerAndUpdatesBalances() {
        BigDecimal amount = new BigDecimal("300.00");
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail(RECEIVER_EMAIL)).thenReturn(Optional.of(receiver));
        when(walletRepository.findByUserId(receiver.getId())).thenReturn(Optional.of(receiverWallet));
        // save() returns its argument (the transaction, with status set by the service).
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response =
                transferService.transfer(SENDER_USER_ID, RECEIVER_EMAIL, amount, IDEMPOTENCY_KEY);

        // The response reflects a completed transfer.
        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.senderBalanceAfter()).isEqualByComparingTo("700.00");

        // Cached balances moved by exactly the amount, in opposite directions.
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("700.00");
        assertThat(receiverWallet.getBalance()).isEqualByComparingTo("350.00");

        // Capture the two ledger entries the service saved together.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        List<LedgerEntry> entries = captor.getValue();

        assertThat(entries).hasSize(2);
        LedgerEntry debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        // DEBIT is on the sender, CREDIT on the receiver.
        assertThat(debit.getWallet()).isSameAs(senderWallet);
        assertThat(credit.getWallet()).isSameAs(receiverWallet);

        // THE double-entry invariant: debit and credit are equal magnitude, so
        // (credit - debit) nets to exactly zero. This is the accounting rule
        // that guarantees money is neither created nor destroyed, only moved.
        assertThat(credit.getAmount().subtract(debit.getAmount())).isEqualByComparingTo("0.00");

        // Each entry records the correct running balance_after.
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("700.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("350.00");
    }

    // ---- Idempotency: same key twice must NOT move money twice -------------

    @Test
    @DisplayName("duplicate Idempotency-Key returns the original transaction WITHOUT creating a new one or touching the ledger")
    void duplicateIdempotencyKey_returnsOriginalAndWritesNothing() {
        Transaction original = Transaction.builder()
                .id(99L)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(new BigDecimal("300.00"))
                .currency("INR")
                .build();
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(original));

        TransferResponse response =
                transferService.transfer(SENDER_USER_ID, RECEIVER_EMAIL, new BigDecimal("300.00"), IDEMPOTENCY_KEY);

        // We get the ORIGINAL transaction back...
        assertThat(response.transactionId()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);

        // ...and, crucially, NO new transaction and NO ledger entries were written.
        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
        // We also short-circuited BEFORE even looking up wallets.
        verify(walletRepository, never()).findByUserId(any());
    }

    // ---- Failure path: insufficient balance --------------------------------

    @Test
    @DisplayName("insufficient balance throws 422-mapped exception and writes NOTHING (no partial debit)")
    void insufficientBalance_throwsAndWritesNothing() {
        BigDecimal amount = new BigDecimal("5000.00"); // sender only has 1000
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail(RECEIVER_EMAIL)).thenReturn(Optional.of(receiver));
        when(walletRepository.findByUserId(receiver.getId())).thenReturn(Optional.of(receiverWallet));

        assertThatThrownBy(() ->
                transferService.transfer(SENDER_USER_ID, RECEIVER_EMAIL, amount, IDEMPOTENCY_KEY))
                .isInstanceOf(InsufficientBalanceException.class);

        // No transaction row, no ledger entries, and balances are UNCHANGED.
        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(receiverWallet.getBalance()).isEqualByComparingTo("50.00");
    }

    /*
     * A test case you might not think of yourself: the EXACT-balance boundary.
     *
     * The balance check uses compareTo(amount) < 0, so sending EXACTLY the
     * whole balance (1000 of 1000) must SUCCEED and leave the wallet at 0.00 -
     * it must not be rejected by an off-by-one/>= mistake, and the resulting
     * zero must be a clean 0.00. This boundary is where a naive `<=` or a
     * BigDecimal scale bug would show up.
     */
    @Test
    @DisplayName("transferring the entire balance succeeds and leaves exactly 0.00")
    void transferringEntireBalance_succeedsToZero() {
        BigDecimal amount = new BigDecimal("1000.00"); // exactly the whole balance
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail(RECEIVER_EMAIL)).thenReturn(Optional.of(receiver));
        when(walletRepository.findByUserId(receiver.getId())).thenReturn(Optional.of(receiverWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response =
                transferService.transfer(SENDER_USER_ID, RECEIVER_EMAIL, amount, IDEMPOTENCY_KEY);

        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("0.00");
    }

    // ---- Failure path: self-transfer ---------------------------------------

    @Test
    @DisplayName("sending money to yourself is rejected as an invalid transfer and writes nothing")
    void selfTransfer_isRejected() {
        // Make the receiver resolve to the SAME wallet as the sender.
        User self = User.builder().id(1L).email("self@test.com").build();
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("self@test.com")).thenReturn(Optional.of(self));
        when(walletRepository.findByUserId(self.getId())).thenReturn(Optional.of(senderWallet)); // same wallet!

        assertThatThrownBy(() ->
                transferService.transfer(SENDER_USER_ID, "self@test.com", new BigDecimal("10.00"), IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidTransferException.class);

        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    // ---- Failure path: unknown recipient -----------------------------------

    @Test
    @DisplayName("unknown recipient email is rejected as an invalid transfer (no account enumeration leak)")
    void unknownRecipient_isRejected() {
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer(SENDER_USER_ID, "ghost@test.com", new BigDecimal("10.00"), IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidTransferException.class);

        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    /*
     * A test case you might not think of yourself: email NORMALISATION.
     *
     * The service lower-cases and trims the recipient email before lookup, so
     * that "  Receiver@Test.com " still resolves to the stored
     * "receiver@test.com". If someone later "optimises" that normalisation
     * away, real users would hit spurious "recipient not found" errors purely
     * due to capitalisation/whitespace. This test pins the behaviour.
     */
    @Test
    @DisplayName("recipient email is normalised (trimmed + lower-cased) before lookup")
    void recipientEmail_isNormalisedBeforeLookup() {
        when(transactionRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletRepository.findByUserId(SENDER_USER_ID)).thenReturn(Optional.of(senderWallet));
        // The service must query with the NORMALISED form, not the raw input.
        when(userRepository.findByEmail(RECEIVER_EMAIL)).thenReturn(Optional.of(receiver));
        when(walletRepository.findByUserId(receiver.getId())).thenReturn(Optional.of(receiverWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transferService.transfer(SENDER_USER_ID, "  Receiver@Test.com ", new BigDecimal("10.00"), IDEMPOTENCY_KEY);

        // Verify the lookup used the normalised email exactly.
        verify(userRepository).findByEmail(RECEIVER_EMAIL);
    }
}
