package com.corebank.query.repository;

import com.corebank.helpers.ResultSets;
import com.corebank.query.dto.BalanceDTO;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AccountQueryRepositoryTest {

    private static final Long ACCOUNT_ID = 1001L;

    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private AccountQueryRepository repository;

    @Test
    void mapsTheBalanceRow() {
        ResultSet row = ResultSets.row()
                .with("id", ACCOUNT_ID)
                .with("total_balance", new BigDecimal("5000.00"))
                .with("balance", new BigDecimal("4750.00"))
                .with("version", 4L)
                .build();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(ACCOUNT_ID)))
                .thenAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(1)).mapRow(row, 0)));

        assertThat(repository.getBalance(ACCOUNT_ID)).contains(new BalanceDTO(ACCOUNT_ID,
                new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L));
    }

    @Test
    void returnsEmptyForAnUnknownAccount() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(ACCOUNT_ID))).thenReturn(List.of());

        assertThat(repository.getBalance(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void selectsTheBalanceWithoutLocking() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(ACCOUNT_ID))).thenReturn(List.of());

        repository.getBalance(ACCOUNT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(ACCOUNT_ID));
        assertThat(sql.getValue())
                .contains("FROM accounts WHERE id = ?")
                .doesNotContain("FOR UPDATE");
    }
}
