package com.corebank.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxWriter writer;

    private static final BalanceUpdatedEvent EVENT = new BalanceUpdatedEvent(
            1001L, new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L);

    @BeforeEach
    void setUp() {
        writer = new OutboxWriter(jdbcTemplate, objectMapper);
    }

    @Test
    void insertsTheEventWithItsAggregateIdTypeAndJsonPayload() throws Exception {
        writer.balanceUpdated(EVENT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), any(), any());
        assertThat(sql.getValue()).contains("INSERT INTO outbox (aggregated_id, type, payload)");
        verify(jdbcTemplate).update(anyString(), any(), any(), any());

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues()).containsExactly(
                1001L, "balance.updated", objectMapper.writeValueAsString(EVENT));
    }

    @Test
    void castsThePayloadToJsonbSoPostgresDoesNotRejectTheInsert() {
        writer.balanceUpdated(EVENT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), any(), any());
        assertThat(sql.getValue()).contains("?::jsonb");
    }

    @Test
    void wrapsDatabaseFailuresInAnIllegalStateException() {
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("primary down"));

        assertThatThrownBy(() -> writer.balanceUpdated(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to write outbox event for account 1001")
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void wrapsSerializationFailures() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

        assertThatThrownBy(() -> new OutboxWriter(jdbcTemplate, failing).balanceUpdated(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account 1001")
                .hasRootCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void exposesTheEventTypeAsAConstantSharedWithTheRelay() {
        assertThat(OutboxWriter.BALANCE_UPDATED).isEqualTo("balance.updated");
    }
}
