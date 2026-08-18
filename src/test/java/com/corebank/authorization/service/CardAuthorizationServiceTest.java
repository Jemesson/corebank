package com.corebank.authorization.service;

import com.corebank.authorization.dto.AccountSnapshot;
import com.corebank.authorization.dto.CardAuthorization;
import com.corebank.authorization.dto.CardAuthorizationResponse;
import com.corebank.authorization.repository.AuthorizationRepository;
import com.corebank.outbox.BalanceUpdatedEvent;
import com.corebank.outbox.OutboxWriter;
import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.AuthorizationNotFoundException;
import com.corebank.shared.exception.InvalidAuthorizationStateException;
import com.corebank.shared.idempotency.IdempotencyManagement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardAuthorizationServiceTest {

    private static final String ENDPOINT = "/api/card/authorization";
    private static final String KEY = "idem-card-1";
    private static final Long ACCOUNT_ID = 1001L;
    private static final String MERCHANT = "PADARIA CENTRAL";
    private static final String CODE = "AUT0123456789ABCD";

    @Mock private AuthorizationRepository repository;
    @Mock private OutboxWriter outboxWriter;
    @Mock private IdempotencyManagement idempotency;

    @InjectMocks private CardAuthorizationService service;

    private static AccountSnapshot snapshot(String totalBalance, String balance, long version) {
        return new AccountSnapshot(ACCOUNT_ID, new BigDecimal(totalBalance), new BigDecimal(balance), version);
    }

    @Nested
    @DisplayName("authorize")
    class Authorize {

        @Test
        void approvesWhenAvailableBalanceCoversTheAmount() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "5000.00", 3)));
            when(repository.applyHold(eq(ACCOUNT_ID), any())).thenReturn(snapshot("5000.00", "4750.00", 4));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT);

            assertThat(response.status()).isEqualTo("APPROVED");
            assertThat(response.reason()).isNull();
            assertThat(response.availableBalance()).isEqualByComparingTo("4750.00");
            assertThat(response.authorizationCode()).matches("AUT[0-9A-F]{16}");
        }

        @Test
        void approvesWhenTheAmountExactlyMatchesTheAvailableBalance() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("300.00", "300.00", 1)));
            when(repository.applyHold(eq(ACCOUNT_ID), any())).thenReturn(snapshot("300.00", "0.00", 2));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("300.00"), MERCHANT);

            assertThat(response.status()).isEqualTo("APPROVED");
        }

        @Test
        void holdsRecordsPublishesAndCompletesInThatOrder() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "5000.00", 3)));
            when(repository.applyHold(ACCOUNT_ID, new BigDecimal("250.00")))
                    .thenReturn(snapshot("5000.00", "4750.00", 4));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT);

            InOrder order = inOrder(idempotency, repository, outboxWriter);
            order.verify(idempotency).reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID);
            order.verify(repository).lockAccount(ACCOUNT_ID);
            order.verify(repository).applyHold(ACCOUNT_ID, new BigDecimal("250.00"));
            order.verify(repository).record(response.authorizationCode(), ACCOUNT_ID,
                    new BigDecimal("250.00"), MERCHANT, "APPROVED");
            order.verify(outboxWriter).balanceUpdated(any(BalanceUpdatedEvent.class));
            order.verify(idempotency).complete(KEY, ENDPOINT, response.authorizationCode());
        }

        @Test
        void publishesTheBalanceReturnedByTheHold() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "5000.00", 3)));
            when(repository.applyHold(eq(ACCOUNT_ID), any())).thenReturn(snapshot("5000.00", "4750.00", 4));

            service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT);

            ArgumentCaptor<BalanceUpdatedEvent> captor = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
            verify(outboxWriter).balanceUpdated(captor.capture());
            assertThat(captor.getValue())
                    .isEqualTo(new BalanceUpdatedEvent(ACCOUNT_ID,
                            new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L));
        }

        @Test
        void deniesWithoutHoldingWhenAvailableBalanceIsShort() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "100.00", 3)));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("100.01"), MERCHANT);

            assertThat(response.status()).isEqualTo("DENIED");
            assertThat(response.reason()).isEqualTo("Insufficient available balance");
            assertThat(response.availableBalance()).isEqualByComparingTo("100.00");

            verify(repository).record(response.authorizationCode(), ACCOUNT_ID,
                    new BigDecimal("100.01"), MERCHANT, "DENIED");
            verify(repository, never()).applyHold(anyLong(), any());
            verify(idempotency).complete(KEY, ENDPOINT, response.authorizationCode());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void deniesWhenOnlyTheTotalBalanceWouldCoverTheAmount() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "200.00", 3)));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("1000.00"), MERCHANT);

            assertThat(response.status()).isEqualTo("DENIED");
        }

        @Test
        void replaysThePreviousDecisionWithTheCurrentBalance() {
            when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.of(CODE));
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(
                    new CardAuthorization(CODE, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT, "APPROVED")));
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "4750.00", 4)));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT);

            assertThat(response).isEqualTo(new CardAuthorizationResponse(CODE, "APPROVED",
                    "Authorization replay already decided", new BigDecimal("4750.00")));
            verify(repository, never()).applyHold(anyLong(), any());
            verify(repository, never()).record(anyString(), anyLong(), any(), anyString(), anyString());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void replayPreservesADeniedDecision() {
            when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.of(CODE));
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(
                    new CardAuthorization(CODE, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT, "DENIED")));
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "100.00", 4)));

            CardAuthorizationResponse response =
                    service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT);

            assertThat(response.status()).isEqualTo("DENIED");
            assertThat(response.authorizationCode()).isEqualTo(CODE);
        }

        @Test
        void failsWhenTheReplayedAuthorizationIsMissing() {
            when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.of(CODE));
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT))
                    .isInstanceOf(AuthorizationNotFoundException.class)
                    .hasMessage("Authorization " + CODE + " not found");
        }

        @Test
        void failsWhenTheAccountDoesNotExist() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorize(KEY, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 1001 not found");

            verify(repository, never()).record(anyString(), anyLong(), any(), anyString(), anyString());
            verify(idempotency, never()).complete(anyString(), anyString(), anyString());
        }

        @Test
        void generatesADistinctAuthorizationCodePerRequest() {
            givenFirstAttempt();
            when(repository.lockAccount(ACCOUNT_ID)).thenReturn(Optional.of(snapshot("5000.00", "10.00", 3)));

            String first = service.authorize(KEY, ACCOUNT_ID, new BigDecimal("50.00"), MERCHANT)
                    .authorizationCode();
            String second = service.authorize(KEY, ACCOUNT_ID, new BigDecimal("50.00"), MERCHANT)
                    .authorizationCode();

            assertThat(first).isNotEqualTo(second);
        }

        private void givenFirstAttempt() {
            when(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).thenReturn(Optional.empty());
        }
    }

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        void settlesTheHoldAndMarksTheAuthorizationCaptured() {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(approved()));
            when(repository.settleHold(ACCOUNT_ID, new BigDecimal("250.00")))
                    .thenReturn(snapshot("4750.00", "4750.00", 5));

            CardAuthorizationResponse response = service.capture(CODE);

            assertThat(response).isEqualTo(new CardAuthorizationResponse(CODE, "CAPTURED", null,
                    new BigDecimal("4750.00")));
            verify(repository).updateStatus(CODE, "CAPTURED");
            verify(outboxWriter).balanceUpdated(new BalanceUpdatedEvent(ACCOUNT_ID,
                    new BigDecimal("4750.00"), new BigDecimal("4750.00"), 5L));
        }

        @Test
        void failsWhenTheAuthorizationDoesNotExist() {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.capture(CODE))
                    .isInstanceOf(AuthorizationNotFoundException.class);

            verify(repository, never()).settleHold(anyLong(), any());
            verifyNoInteractions(outboxWriter);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CAPTURED", "REVERSED", "DENIED"})
        void rejectsAnAuthorizationThatIsNotApproved(String status) {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(
                    new CardAuthorization(CODE, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT, status)));

            assertThatThrownBy(() -> service.capture(CODE))
                    .isInstanceOf(InvalidAuthorizationStateException.class)
                    .hasMessage("Authorization " + CODE + " is in state " + status + ", expected APPROVED");

            verify(repository, never()).settleHold(anyLong(), any());
            verify(repository, never()).updateStatus(anyString(), anyString());
            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("reverse")
    class Reverse {

        @Test
        void releasesTheHoldAndMarksTheAuthorizationReversed() {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(approved()));
            when(repository.releaseHold(ACCOUNT_ID, new BigDecimal("250.00")))
                    .thenReturn(snapshot("5000.00", "5000.00", 5));

            CardAuthorizationResponse response = service.reverse(CODE);

            assertThat(response).isEqualTo(new CardAuthorizationResponse(CODE, "REVERSED", null,
                    new BigDecimal("5000.00")));
            verify(repository).updateStatus(CODE, "REVERSED");
            verify(outboxWriter).balanceUpdated(new BalanceUpdatedEvent(ACCOUNT_ID,
                    new BigDecimal("5000.00"), new BigDecimal("5000.00"), 5L));
        }

        @Test
        void failsWhenTheAuthorizationDoesNotExist() {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reverse(CODE))
                    .isInstanceOf(AuthorizationNotFoundException.class);

            verify(repository, never()).releaseHold(anyLong(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"REVERSED", "CAPTURED", "DENIED"})
        void rejectsAnAuthorizationThatIsNotApproved(String status) {
            when(repository.lockAuthorization(CODE)).thenReturn(Optional.of(
                    new CardAuthorization(CODE, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT, status)));

            assertThatThrownBy(() -> service.reverse(CODE))
                    .isInstanceOf(InvalidAuthorizationStateException.class);

            verify(repository, never()).releaseHold(anyLong(), any());
            verify(repository, never()).updateStatus(anyString(), anyString());
            verifyNoInteractions(outboxWriter);
        }
    }

    private static CardAuthorization approved() {
        return new CardAuthorization(CODE, ACCOUNT_ID, new BigDecimal("250.00"), MERCHANT, "APPROVED");
    }
}
