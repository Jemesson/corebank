package com.corebank.query.repository;

import com.corebank.query.dto.PixStatementReadDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Le da REPLICA. Extrato e a consulta mais pesada e nao precisa ser exata. */
@Repository
public class PixQueryRepository {

    private static final String SELECT_STATEMENT = """
            SELECT end_to_end_id, target_pix_key, value, operation_type, status, created_at
              FROM pix_transactions
             WHERE origin_account_id = ?
             ORDER BY created_at DESC
             LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PixQueryRepository(@Qualifier("replicaJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PixStatementReadDTO> getPixStatement(Long accountId, int limit, int offset) {
        return jdbcTemplate.query(SELECT_STATEMENT, (rs, rowNum) -> new PixStatementReadDTO(
                rs.getString("end_to_end_id"),
                rs.getString("target_pix_key"),
                rs.getBigDecimal("value"),
                rs.getString("operation_type"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        ), accountId, limit, offset);
    }
}
