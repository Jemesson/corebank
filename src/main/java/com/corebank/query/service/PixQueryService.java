package com.corebank.query.service;

import com.corebank.query.dto.BalanceDTO;
import com.corebank.query.dto.PixStatementReadDTO;
import com.corebank.query.repository.AccountQueryRepository;
import com.corebank.query.repository.PixQueryRepository;
import com.corebank.shared.cache.BalanceCached;
import com.corebank.shared.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Caminho de leitura: Redis -> replica. O primario nunca e tocado aqui.
 *
 * Este e o caminho que absorve os ~90% de carga de consulta descritos no
 * cenario. A contrapartida e consistencia eventual, aceitavel porque o
 * requisito permite ate 5s de defasagem no saldo exibido.
 *
 * ATENCAO: autorizacao de cartao NAO passa por aqui. Ver CardAuthorizationService.
 */
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

    /** Cache-aside: o cache e populado tanto no miss quanto pelo relay do outbox. */
    public BalanceDTO getBalance(Long accountId) {
        return balanceCache.get(accountId).orElseGet(() -> {
            BalanceDTO balance = accountQueryRepository.getBalance(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            balanceCache.put(balance);
            return balance;
        });
    }
}
