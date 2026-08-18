package com.corebank.query.service;

import com.corebank.query.dto.BalanceDTO;
import com.corebank.query.dto.PixStatementReadDTO;
import com.corebank.query.repository.AccountQueryRepository;
import com.corebank.query.repository.PixQueryRepository;
import com.corebank.shared.cache.BalanceCached;
import com.corebank.shared.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PixQueryService {

    private final PixQueryRepository repository;
    private final AccountQueryRepository accountQueryRepository;
    private final BalanceCached balanceCache;

    public PixQueryService(PixQueryRepository repository,
                           AccountQueryRepository accountQueryRepository,
                           BalanceCached balanceCache) {
        this.repository = repository;
        this.accountQueryRepository = accountQueryRepository;
        this.balanceCache = balanceCache;
    }

    public List<PixStatementReadDTO> getPixStatement(Long accountId, int limit, int offset) {
        return repository.getPixStatement(accountId, limit, offset);
    }

    public BalanceDTO getBalance(Long accountId) {
        return balanceCache.get(accountId).orElseGet(() -> {
            BalanceDTO balance = accountQueryRepository.getBalance(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            balanceCache.put(balance);
            return balance;
        });
    }
}
