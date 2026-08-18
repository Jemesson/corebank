package com.corebank.query.repository;

import com.corebank.helpers.ResultSets;
import com.corebank.query.dto.PixStatementReadDTO;

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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class PixQueryRepositoryTest {

    private static final Long ACCOUNT_ID = 1001L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-18T10:00:00Z");

    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private PixQueryRepository repository;

    private void stubRows(int limit, int offset, ResultSet... rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(ACCOUNT_ID), eq(limit), eq(offset)))
                .thenAnswer(i -> {
                    RowMapper<Object> mapper = i.getArgument(1);
                    List<Object> mapped = new java.util.ArrayList<>();
                    for (int r = 0; r < rows.length; r++) {
                        mapped.add(mapper.mapRow(rows[r], r));
                    }
                    return mapped;
                });
    }

    @Test
    void mapsAStatementRow() {
        stubRows(20, 0, ResultSets.row()
                .with("end_to_end_id", "E123")
                .with("target_pix_key", "target@pix.com")
                .with("value", new BigDecimal("100.00"))
                .with("operation_type", "SENT")
                .with("status", "COMPLETED")
                .with("created_at", CREATED_AT)
                .build());

        assertThat(repository.getPixStatement(ACCOUNT_ID, 20, 0))
                .containsExactly(new PixStatementReadDTO("E123", "target@pix.com",
                        new BigDecimal("100.00"), "SENT", "COMPLETED", CREATED_AT));
    }

    @Test
    void returnsAnEmptyStatementWhenThereIsNoTransaction() {
        stubRows(20, 0);

        assertThat(repository.getPixStatement(ACCOUNT_ID, 20, 0)).isEmpty();
    }

    @Test
    void appliesLimitAndOffsetAndOrdersNewestFirst() {
        stubRows(5, 10);

        repository.getPixStatement(ACCOUNT_ID, 5, 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(ACCOUNT_ID), eq(5), eq(10));
        assertThat(sql.getValue())
                .contains("WHERE origin_account_id = ?")
                .contains("ORDER BY created_at DESC")
                .contains("LIMIT ? OFFSET ?");
    }
}
