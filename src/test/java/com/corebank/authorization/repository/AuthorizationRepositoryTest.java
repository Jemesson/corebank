package com.corebank.authorization.repository;

import com.corebank.authorization.dto.AccountSnapshot;
import com.corebank.authorization.dto.CardAuthorization;
import com.corebank.helpers.ResultSets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationRepositoryTest {

    private static final Long ACCOUNT_ID = 1001L;
    private static final String CODE = "AUT0123456789ABCD";

    @Mock private JdbcTemplate primary;

    @InjectMocks private AuthorizationRepository repository;

    private static ResultSet accountRow(String total, String available, long version) {
        return ResultSets.row()
                .with("id", ACCOUNT_ID)
                .with("total_balance", new BigDecimal(total))
                .with("balance", new BigDecimal(available))
                .with("version", version)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> map(Object rowMapperArg, ResultSet... rows) throws Exception {
        RowMapper<T> mapper = (RowMapper<T>) rowMapperArg;
        List<T> mapped = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            mapped.add(mapper.mapRow(rows[i], i));
        }
        return mapped;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<String> stubAccountQuery(ResultSet... rows) {
        when(primary.query(anyString(), any(RowMapper.class), eq(ACCOUNT_ID)))
                .thenAnswer(i -> map(i.getArgument(1), rows));
        return ArgumentCaptor.forClass(String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAccountMapsTheRowAndLocksItForUpdate() {
        ArgumentCaptor<String> sql = stubAccountQuery(accountRow("5000.00", "4750.00", 4));

        Optional<AccountSnapshot> found = repository.lockAccount(ACCOUNT_ID);

        assertThat(found).contains(new AccountSnapshot(ACCOUNT_ID,
                new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L));
        verify(primary).query(sql.capture(), any(RowMapper.class), eq(ACCOUNT_ID));
        assertThat(sql.getValue()).contains("FROM accounts").contains("FOR UPDATE");
    }

    @Test
    void lockAccountReturnsEmptyForAnUnknownAccount() {
        stubAccountQuery();

        assertThat(repository.lockAccount(ACCOUNT_ID)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyHoldSubtractsFromAvailableAndLeavesTheTotalAlone() {
        when(primary.queryForObject(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(i -> map(i.getArgument(1), accountRow("5000.00", "4750.00", 4)).get(0));

        AccountSnapshot updated = repository.applyHold(ACCOUNT_ID, new BigDecimal("250.00"));

        assertThat(updated.balance()).isEqualByComparingTo("4750.00");
        verify(primary).queryForObject(anyString(), any(RowMapper.class),
                eq(new BigDecimal("-250.00")), eq(BigDecimal.ZERO), eq(ACCOUNT_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void settleHoldSubtractsFromTheTotalAndLeavesAvailableAlone() {
        when(primary.queryForObject(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(i -> map(i.getArgument(1), accountRow("4750.00", "4750.00", 5)).get(0));

        repository.settleHold(ACCOUNT_ID, new BigDecimal("250.00"));

        verify(primary).queryForObject(anyString(), any(RowMapper.class),
                eq(BigDecimal.ZERO), eq(new BigDecimal("-250.00")), eq(ACCOUNT_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseHoldGivesTheAmountBackToAvailable() {
        when(primary.queryForObject(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(i -> map(i.getArgument(1), accountRow("5000.00", "5000.00", 5)).get(0));

        repository.releaseHold(ACCOUNT_ID, new BigDecimal("250.00"));

        verify(primary).queryForObject(anyString(), any(RowMapper.class),
                eq(new BigDecimal("250.00")), eq(BigDecimal.ZERO), eq(ACCOUNT_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void balanceUpdatesBumpTheVersionAndReturnTheNewState() {
        when(primary.queryForObject(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(i -> map(i.getArgument(1), accountRow("5000.00", "4750.00", 4)).get(0));

        repository.applyHold(ACCOUNT_ID, new BigDecimal("250.00"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(primary).queryForObject(sql.capture(), any(RowMapper.class), any(), any(), any());
        assertThat(sql.getValue())
                .contains("UPDATE accounts")
                .contains("version = version + 1")
                .contains("RETURNING id, total_balance, balance, version");
    }

    @Test
    void recordInsertsTheAuthorizationWithItsStatus() {
        repository.record(CODE, ACCOUNT_ID, new BigDecimal("250.00"), "PADARIA", "APPROVED");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(primary).update(sql.capture(), eq(CODE), eq(ACCOUNT_ID),
                eq(new BigDecimal("250.00")), eq("PADARIA"), eq("APPROVED"));
        assertThat(sql.getValue()).contains("INSERT INTO card_authorizations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAuthorizationMapsTheRowAndLocksItForUpdate() {
        ResultSet row = ResultSets.row()
                .with("authorization_code", CODE)
                .with("account_id", ACCOUNT_ID)
                .with("amount", new BigDecimal("250.00"))
                .with("merchant", "PADARIA")
                .with("status", "APPROVED")
                .build();
        when(primary.query(anyString(), any(RowMapper.class), eq(CODE)))
                .thenAnswer(i -> map(i.getArgument(1), row));

        Optional<CardAuthorization> found = repository.lockAuthorization(CODE);

        assertThat(found).contains(new CardAuthorization(CODE, ACCOUNT_ID,
                new BigDecimal("250.00"), "PADARIA", "APPROVED"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(primary).query(sql.capture(), any(RowMapper.class), eq(CODE));
        assertThat(sql.getValue()).contains("FROM card_authorizations").contains("FOR UPDATE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAuthorizationReturnsEmptyForAnUnknownCode() {
        when(primary.query(anyString(), any(RowMapper.class), eq(CODE))).thenReturn(List.of());

        assertThat(repository.lockAuthorization(CODE)).isEmpty();
    }

    @Test
    void updateStatusStampsTheSettlementTime() {
        repository.updateStatus(CODE, "CAPTURED");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(primary).update(sql.capture(), eq("CAPTURED"), eq(CODE));
        assertThat(sql.getValue())
                .contains("UPDATE card_authorizations")
                .contains("settled_at = NOW()");
    }
}
