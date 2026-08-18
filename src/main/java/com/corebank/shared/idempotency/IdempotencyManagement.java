package com.corebank.shared.idempotency;

import com.corebank.shared.exception.DuplicateRequestInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class IdempotencyManagement {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyManagement.class);

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyManagement(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> reserveOrReplay(String key, String endpoint, Long accountId) {
        var inserted = jdbcTemplate.update("""
                INSERT INTO idempotency_keys (key, endpoint, account_id, status)
                VALUES (?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (key, endpoint) DO NOTHING
                """, key, endpoint, accountId);

        if (inserted == 1) {
            return Optional.empty();
        }

        var responses = jdbcTemplate.queryForList("""
                SELECT response_ref FROM idempotency_keys WHERE key = ? AND endpoint = ?
                """, String.class, key, endpoint);

        // Nao usar stream().findFirst(): response_ref e NULL enquanto a requisicao
        // concorrente esta IN_PROGRESS, e Optional.of(null) estoura NullPointerException.
        var response = responses.isEmpty() ? null : responses.get(0);
        if (response == null) {
            throw new DuplicateRequestInProgressException(key);
        }

        log.info("Idempotency replay: endpoint={} key={} response={}", endpoint, key, response);
        return Optional.of(response);
    }

    public void complete(String key, String endpoint, String responseRef) {
        jdbcTemplate.update("""
                UPDATE idempotency_keys
                   SET status = 'COMPLETED', response_ref = ?
                 WHERE key = ? AND endpoint = ?
                """, responseRef, key, endpoint);
    }
}
