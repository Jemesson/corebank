package com.corebank.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    public static final String BALANCE_UPDATED = "balance.updated";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWriter(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbcTemplate,
                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void balanceUpdated(BalanceUpdatedEvent event) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO outbox (aggregated_id, type, payload) VALUES (?, ?, ?::jsonb)",
                    event.accountId(), BALANCE_UPDATED, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to write outbox event for account " + event.accountId(), e);
        }
    }
}
