package com.corebank.authorization.repository;

import com.corebank.authorization.dto.AccountSnapshot;
import com.corebank.authorization.dto.CardAuthorization;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class AuthorizationRepository {

    private final JdbcTemplate primary;

    public AuthorizationRepository(@Qualifier("primaryJdbcTemplate") JdbcTemplate primary) {
        this.primary = primary;
    }

    public Optional<AccountSnapshot> lockAccount(Long accountId) {
        return primary.query("""
                SELECT id, total_balance, balance, version
                  FROM accounts
                 WHERE id = ?
                   FOR UPDATE
                """, (rs, rowNum) -> new AccountSnapshot(
                        rs.getLong("id"), rs.getBigDecimal("total_balance"),
                        rs.getBigDecimal("balance"), rs.getLong("version")),
                accountId).stream().findFirst();
    }

    public AccountSnapshot applyHold(Long accountId, BigDecimal amount) {
        return updateBalances(accountId, amount.negate(), BigDecimal.ZERO);
    }

    public AccountSnapshot settleHold(Long accountId, BigDecimal amount) {
        return updateBalances(accountId, BigDecimal.ZERO, amount.negate());
    }

    public AccountSnapshot releaseHold(Long accountId, BigDecimal amount) {
        return updateBalances(accountId, amount, BigDecimal.ZERO);
    }

    private AccountSnapshot updateBalances(Long accountId, BigDecimal balanceDelta, BigDecimal totalDelta) {
        return primary.queryForObject("""
                UPDATE accounts
                   SET balance = balance + ?,
                       total_balance = total_balance + ?,
                       version = version + 1
                 WHERE id = ?
             RETURNING id, total_balance, balance, version
                """, (rs, rowNum) -> new AccountSnapshot(
                        rs.getLong("id"), rs.getBigDecimal("total_balance"),
                        rs.getBigDecimal("balance"), rs.getLong("version")),
                balanceDelta, totalDelta, accountId);
    }

    public void record(String authorizationCode, Long accountId, BigDecimal amount,
                       String merchant, String status) {
        primary.update("""
                INSERT INTO card_authorizations (authorization_code, account_id, amount, merchant, status)
                VALUES (?, ?, ?, ?, ?)
                """, authorizationCode, accountId, amount, merchant, status);
    }

    public Optional<CardAuthorization> lockAuthorization(String authorizationCode) {
        return primary.query("""
                SELECT authorization_code, account_id, amount, merchant, status
                  FROM card_authorizations
                 WHERE authorization_code = ?
                   FOR UPDATE
                """, (rs, rowNum) -> new CardAuthorization(
                        rs.getString("authorization_code"), rs.getLong("account_id"),
                        rs.getBigDecimal("amount"), rs.getString("merchant"), rs.getString("status")),
                authorizationCode).stream().findFirst();
    }

    public void updateStatus(String authorizationCode, String status) {
        primary.update("""
                UPDATE card_authorizations SET status = ?, settled_at = NOW()
                 WHERE authorization_code = ?
                """, status, authorizationCode);
    }
}
