package com.corebank.outbox;

import com.corebank.query.dto.BalanceDTO;
import com.corebank.shared.cache.BalanceCached;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
/**
 * This class is responsible for relaying events from the outbox table to the cache.
 * It polls the outbox table for unprocessed events, processes them, and marks them as processed.
 * OutboxRelay
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbcTemplate;
    private final BalanceCached balanceCached;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public OutboxRelay(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbcTemplate,
                       BalanceCached balanceCache,
                       ObjectMapper objectMapper,
                       @Value("${corebank.outbox.batch-size}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.balanceCached = balanceCache;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${corebank.outbox.poll-interval}")
    @Transactional
    public void drain() {
        var events = jdbcTemplate.query(
                """
                SELECT id, aggregated_id, type, payload
                  FROM outbox
                 WHERE processed_at IS NULL
                 ORDER BY id
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"), rs.getLong("aggregated_id"),
                        rs.getString("type"), rs.getString("payload")),
                batchSize);

        if (events.isEmpty()) {
            return;
        }

        List<Long> processed = new ArrayList<>(events.size());
        for (OutboxRecord event : events) {
            try {
                handle(event);
                processed.add(event.id());
            } catch (Exception e) {
                log.error("Failed to process outbox event {} (type={}): {}",
                        event.id(), event.type(), e.toString());
            }
        }

        if (!processed.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "UPDATE outbox SET processed_at = NOW() WHERE id = ?",
                    processed.stream().map(id -> new Object[]{id}).toList());
            log.debug("{} outbox event(s) processed", processed.size());
        }
    }

    private void handle(OutboxRecord record) throws Exception {
        if (!OutboxWriter.BALANCE_UPDATED.equals(record.type())) {
            log.warn("Unknown event type in outbox: {}", record.type());
            return;
        }
        var event = objectMapper.readValue(record.payload(), BalanceUpdatedEvent.class);
        balanceCached.putIfNewer(new BalanceDTO(
                event.accountId(), event.totalBalance(), event.balance(), event.version()));
    }

    private record OutboxRecord(Long id, Long aggregatedId, String type, String payload) {}
}
