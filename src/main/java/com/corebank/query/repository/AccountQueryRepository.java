package com.corebank.query.repository;

import com.corebank.query.dto.BalanceDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Le da REPLICA. Sem o @Qualifier explicito o Spring injetaria o JdbcTemplate
 * marcado como @Primary e toda a leitura voltaria para o banco primario - que e
 * exatamente o gargalo que a replica existe para resolver.
 */
@Repository
public class AccountQueryRepository {

    private static final String SELECT_BALANCE =
            "SELECT id, total_balance, balance, version FROM accounts WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public AccountQueryRepository(@Qualifier("replicaJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<BalanceDTO> getBalance(Long accountId) {
        var list = jdbcTemplate.query(SELECT_BALANCE, (rs, rowNum) -> new BalanceDTO(
                rs.getLong("id"),
                rs.getBigDecimal("total_balance"),
                rs.getBigDecimal("balance"),
                rs.getLong("version")
        ), accountId);

        return list.stream().findFirst();
    }
}
