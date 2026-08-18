package com.corebank.command.repository;

import com.corebank.command.entity.PixTransaction;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface PixTransactionWriteRepository extends CrudRepository<PixTransaction, UUID> {
}
