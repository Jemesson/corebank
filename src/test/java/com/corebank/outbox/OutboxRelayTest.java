package com.corebank.outbox;

import com.corebank.helpers.ResultSets;
import com.corebank.query.dto.BalanceDTO;
import com.corebank.shared.cache.BalanceCached;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int BATCH_SIZE = 200;

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BalanceCached balanceCached;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(jdbcTemplate, balanceCached, objectMapper, BATCH_SIZE);
    }
    @SuppressWarnings("unchecked")
    private void givenPendingRows(ResultSet... rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(BATCH_SIZE)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    List<Object> mapped = new ArrayList<>();
                    for (int i = 0; i < rows.length; i++) {
                        mapped.add(mapper.mapRow(rows[i], i));
                    }
                    return mapped;
                });
    }

    private static ResultSet outboxRow(long id, long accountId, String type, String payload) {
        return ResultSets.row()
                .with("id", id)
                .with("aggregated_id", accountId)
                .with("type", type)
                .with("payload", payload)
                .build();
    }

    private static String balancePayload(long accountId, String total, String available, long version) {
        return """
               {"accountId":%d,"totalBalance":%s,"balance":%s,"version":%d}
               """.formatted(accountId, total, available, version);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> capturedBatchArgs() {
        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void doesNothingWhenThereIsNoPendingEvent() {
        givenPendingRows();

        relay.drain();

        verifyNoInteractions(balanceCached);
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void appliesABalanceEventToTheCache() {
        givenPendingRows(outboxRow(1L, 1001L, "balance.updated",
                balancePayload(1001L, "5000.00", "4750.00", 4L)));

        relay.drain();

        verify(balanceCached).putIfNewer(new BalanceDTO(1001L,
                new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L));
    }

    @Test
    void marksEveryProcessedEventInASingleBatch() {
        givenPendingRows(
                outboxRow(1L, 1001L, "balance.updated", balancePayload(1001L, "5000.00", "4750.00", 4L)),
                outboxRow(2L, 1002L, "balance.updated", balancePayload(1002L, "900.00", "900.00", 1L)));

        relay.drain();

        assertThat(capturedBatchArgs()).containsExactly(new Object[]{1L}, new Object[]{2L});
    }

    @Test
    void readsOnlyUnprocessedRowsLockedWithSkipLocked() {
        givenPendingRows(outboxRow(1L, 1001L, "balance.updated",
                balancePayload(1001L, "5000.00", "4750.00", 4L)));

        relay.drain();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(BATCH_SIZE));
        assertThat(sql.getValue())
                .contains("WHERE processed_at IS NULL")
                .contains("ORDER BY id")
                .contains("LIMIT ?")
                .contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void keepsProcessingAfterAFailedEventAndLeavesItPending() {
        givenPendingRows(
                outboxRow(1L, 1001L, "balance.updated", "{corrupt-json"),
                outboxRow(2L, 1002L, "balance.updated", balancePayload(1002L, "900.00", "900.00", 1L)));

        relay.drain();

        verify(balanceCached).putIfNewer(new BalanceDTO(1002L,
                new BigDecimal("900.00"), new BigDecimal("900.00"), 1L));
        assertThat(capturedBatchArgs()).containsExactly(new Object[]{2L});
    }

    @Test
    void leavesAnEventPendingWhenTheCacheWriteBlowsUp() {
        givenPendingRows(outboxRow(1L, 1001L, "balance.updated",
                balancePayload(1001L, "5000.00", "4750.00", 4L)));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis exploded"))
                .when(balanceCached).putIfNewer(any());

        assertThatCode(relay::drain).doesNotThrowAnyException();

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void skipsAnUnknownEventTypeButStillMarksItProcessed() {
        givenPendingRows(outboxRow(7L, 1001L, "account.opened", "{}"));

        relay.drain();

        verifyNoInteractions(balanceCached);
        assertThat(capturedBatchArgs()).containsExactly(new Object[]{7L});
    }

    @Test
    void limitsTheQueryToTheConfiguredBatchSize() {
        OutboxRelay smallBatch = new OutboxRelay(jdbcTemplate, balanceCached, objectMapper, 5);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5))).thenReturn(List.of());

        smallBatch.drain();

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(5));
    }

    @Test
    void propagatesAFailureToReadTheOutbox() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(BATCH_SIZE)))
                .thenThrow(new DataAccessResourceFailureException("primary down"));

        assertThatThrownBy(relay::drain).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
