package com.corebank.command.service;

import com.corebank.command.dto.PixPaymentResponse;
import com.corebank.command.entity.Account;
import com.corebank.command.entity.PixTransaction;
import com.corebank.command.repository.AccountWriteRepository;
import com.corebank.command.repository.PixTransactionWriteRepository;
import com.corebank.helpers.Accounts;
import com.corebank.outbox.BalanceUpdatedEvent;
import com.corebank.outbox.OutboxWriter;
import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.idempotency.IdempotencyManagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PixCommandServiceTest {

    private static final String ENDPOINT = "/api/pix/payment";
    private static final String KEY = "idem-key-1";
    private static final Long ACCOUNT_ID = 1001L;
    private static final String TARGET_PIX = "target@pix.com";

    @Mock private AccountWriteRepository accountRepository;
    @Mock private PixTransactionWriteRepository pixRepository;
    @Mock private OutboxWriter outboxWriter;
    @Mock private IdempotencyManagement idempotency;

    @InjectMocks private PixCommandService service;

    private Account account;

    @BeforeEach
    void setUp() {
        account = Accounts.with(ACCOUNT_ID, "5000.00", "5000.00", 3L);
    }

    @Test
    void debitsTheAccountAndReturnsCompleted() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        PixPaymentResponse response = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.endToEndId()).startsWith("E");
        assertThat(account.getBalance()).isEqualByComparingTo("4900.00");
        assertThat(account.getTotalBalance()).isEqualByComparingTo("4900.00");
    }

    @Test
    void persistsTheTransactionWithTheGeneratedEndToEndId() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        PixPaymentResponse response = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00"));

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(pixRepository).save(captor.capture());
        assertThat(captor.getValue()).extracting("endToEndId", "originAccountId", "targetPix",
                        "value", "type", "status")
                .containsExactly(response.endToEndId(), ACCOUNT_ID, TARGET_PIX,
                        new BigDecimal("100.00"), "SENT", "COMPLETED");
        assertThat(captor.getValue()).extracting("createdAt").isNotNull();
    }

    @Test
    void writesTheBalanceUpdatedEventWithThePostDebitState() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00"));

        ArgumentCaptor<BalanceUpdatedEvent> captor = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        verify(outboxWriter).balanceUpdated(captor.capture());
        BalanceUpdatedEvent event = captor.getValue();
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.balance()).isEqualByComparingTo("4900.00");
        assertThat(event.totalBalance()).isEqualByComparingTo("4900.00");
        assertThat(event.version()).isEqualTo(3L);
    }

    @Test
    void completesTheIdempotencyKeyWithTheEndToEndIdAfterPersisting() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        PixPaymentResponse response = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00"));

        InOrder order = inOrder(idempotency, accountRepository, pixRepository, outboxWriter);
        order.verify(idempotency).reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID);
        order.verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        order.verify(accountRepository).save(account);
        order.verify(pixRepository).save(any(PixTransaction.class));
        order.verify(outboxWriter).balanceUpdated(any(BalanceUpdatedEvent.class));
        order.verify(idempotency).complete(KEY, ENDPOINT, response.endToEndId());
    }

    @Test
    void generatesADistinctEndToEndIdPerPayment() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        String first = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("10.00")).endToEndId();
        String second = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("10.00")).endToEndId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void replaysThePreviousEndToEndIdWithoutTouchingTheAccount() {
        when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.of("E123-original"));

        PixPaymentResponse response = service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00"));

        assertThat(response).isEqualTo(new PixPaymentResponse("E123-original", "REPLAYED"));
        verifyNoInteractions(accountRepository, pixRepository, outboxWriter);
        verify(idempotency, never()).complete(anyString(), anyString(), anyString());
    }

    @Test
    void failsWhenTheAccountDoesNotExist() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("100.00")))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account 1001 not found");

        verifyNoInteractions(pixRepository, outboxWriter);
        verify(idempotency, never()).complete(anyString(), anyString(), anyString());
    }

    @Test
    void propagatesInsufficientBalanceWithoutRecordingAnything() {
        givenFirstAttempt();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.payment(KEY, ACCOUNT_ID, TARGET_PIX, new BigDecimal("5000.01")))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(pixRepository, outboxWriter);
        verify(idempotency, never()).complete(anyString(), anyString(), anyString());
    }

    private void givenFirstAttempt() {
        when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.empty());
    }
}
