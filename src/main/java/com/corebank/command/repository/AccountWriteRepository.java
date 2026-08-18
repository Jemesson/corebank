package com.corebank.command.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import com.corebank.command.entity.Account;

import java.util.Optional;

public interface AccountWriteRepository extends CrudRepository<Account, Long> {
    @Query("SELECT id, document, total_balance, balance, version FROM accounts WHERE id = :id FOR UPDATE")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
