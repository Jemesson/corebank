package com.corebank.command.entity;

import com.corebank.helpers.Accounts;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAmountException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Nested
    @DisplayName("withdraw (PIX enviado)")
    class Withdraw {

        @Test
        void debitsAvailableAndTotalBalance() {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            account.withdraw(new BigDecimal("100.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("4900.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("4900.00");
        }

        @Test
        void allowsWithdrawingTheEntireAvailableBalance() {
            Account account = Accounts.with(1001L, "300.00", "300.00");

            account.withdraw(new BigDecimal("300.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void rejectsAmountAboveAvailableBalanceLeavingStateUntouched() {
            Account account = Accounts.with(1001L, "5000.00", "100.00");

            assertThatThrownBy(() -> account.withdraw(new BigDecimal("100.01")))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("account 1001")
                    .hasMessageContaining("available=100.00")
                    .hasMessageContaining("requested=100.01");

            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("5000.00");
        }

        @Test
        void ignoresBalanceHeldByCardAuthorizationWhenCheckingAvailability() {
            Account account = Accounts.with(1001L, "5000.00", "1000.00");

            assertThatThrownBy(() -> account.withdraw(new BigDecimal("2000.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-0.01", "-100.00"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            assertThatThrownBy(() -> account.withdraw(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining(amount);
        }

        @ParameterizedTest
        @NullSource
        void rejectsNullAmount(BigDecimal amount) {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            assertThatThrownBy(() -> account.withdraw(amount))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("null");
        }
    }

    @Nested
    @DisplayName("hold (autorizacao de cartao)")
    class Hold {

        @Test
        void reservesFromAvailableAndKeepsTotalIntact() {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            account.hold(new BigDecimal("250.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("4750.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("5000.00");
        }

        @Test
        void rejectsAmountAboveAvailableBalance() {
            Account account = Accounts.with(1001L, "5000.00", "200.00");

            assertThatThrownBy(() -> account.hold(new BigDecimal("200.01")))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(account.getBalance()).isEqualByComparingTo("200.00");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-5.00"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            assertThatThrownBy(() -> account.hold(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("settleHold (captura)")
    class SettleHold {

        @Test
        void debitsOnlyTheTotalBalanceSinceAvailableWasAlreadyReserved() {
            Account account = Accounts.with(1001L, "5000.00", "4750.00");

            account.settleHold(new BigDecimal("250.00"));

            assertThat(account.getTotalBalance()).isEqualByComparingTo("4750.00");
            assertThat(account.getBalance()).isEqualByComparingTo("4750.00");
        }

        @Test
        void doesNotValidateAvailableBalance() {
            Account account = Accounts.with(1001L, "1000.00", "0.00");

            account.settleHold(new BigDecimal("1000.00"));

            assertThat(account.getTotalBalance()).isEqualByComparingTo("0.00");
            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-1.00"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            assertThatThrownBy(() -> account.settleHold(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("releaseHold (estorno)")
    class ReleaseHold {

        @Test
        void givesTheAmountBackToAvailableAndKeepsTotalIntact() {
            Account account = Accounts.with(1001L, "5000.00", "4750.00");

            account.releaseHold(new BigDecimal("250.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("5000.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("5000.00");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-1.00"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = Accounts.with(1001L, "5000.00", "4750.00");

            assertThatThrownBy(() -> account.releaseHold(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("ciclo completo de autorizacao")
    class FullLifecycle {

        @Test
        void holdThenCaptureLeavesBothBalancesDebited() {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            account.hold(new BigDecimal("250.00"));
            account.settleHold(new BigDecimal("250.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("4750.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("4750.00");
        }

        @Test
        void holdThenReversalRestoresTheOriginalBalances() {
            Account account = Accounts.with(1001L, "5000.00", "5000.00");

            account.hold(new BigDecimal("250.00"));
            account.releaseHold(new BigDecimal("250.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("5000.00");
            assertThat(account.getTotalBalance()).isEqualByComparingTo("5000.00");
        }

        @Test
        void exposesIdentityFields() {
            Account account = Accounts.with(1001L, "5000.00", "5000.00", 7L);

            assertThat(account.getId()).isEqualTo(1001L);
            assertThat(account.getDocument()).isEqualTo("12345678901");
            assertThat(account.getVersion()).isEqualTo(7L);
        }
    }
}
