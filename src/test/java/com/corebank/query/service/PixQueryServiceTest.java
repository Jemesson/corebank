package com.corebank.query.service;

import com.corebank.query.dto.BalanceDTO;
import com.corebank.query.dto.PixStatementReadDTO;
import com.corebank.query.repository.AccountQueryRepository;
import com.corebank.query.repository.PixQueryRepository;
import com.corebank.shared.cache.BalanceCached;
import com.corebank.shared.exception.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PixQueryServiceTest {

    private static final Long ACCOUNT_ID = 1001L;

    @Mock private PixQueryRepository repository;
    @Mock private AccountQueryRepository accountQueryRepository;
    @Mock private BalanceCached balanceCache;

    @InjectMocks private PixQueryService service;

    private static BalanceDTO balance(String total, String available, Long version) {
        return new BalanceDTO(ACCOUNT_ID, new BigDecimal(total), new BigDecimal(available), version);
    }

    @Test
    void servesTheBalanceFromCacheWithoutTouchingTheReplica() {
        BalanceDTO cached = balance("5000.00", "4750.00", 4L);
        when(balanceCache.get(ACCOUNT_ID)).thenReturn(Optional.of(cached));

        assertThat(service.getBalance(ACCOUNT_ID)).isEqualTo(cached);

        verifyNoInteractions(accountQueryRepository);
        verify(balanceCache, never()).put(any());
    }

    @Test
    void fallsBackToTheReplicaAndWarmsTheCacheOnMiss() {
        BalanceDTO fromDb = balance("5000.00", "4750.00", 4L);
        when(balanceCache.get(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(accountQueryRepository.getBalance(ACCOUNT_ID)).thenReturn(Optional.of(fromDb));

        assertThat(service.getBalance(ACCOUNT_ID)).isEqualTo(fromDb);

        verify(balanceCache).put(fromDb);
    }

    @Test
    void failsWhenTheAccountIsMissingInBothCacheAndReplica() {
        when(balanceCache.get(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(accountQueryRepository.getBalance(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBalance(ACCOUNT_ID))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account 1001 not found");

        verify(balanceCache, never()).put(any());
    }

    @Test
    void delegatesTheStatementToTheReplicaRepository() {
        var entry = new PixStatementReadDTO("E123", "target@pix.com", new BigDecimal("100.00"),
                "SENT", "COMPLETED", Instant.parse("2026-08-18T10:00:00Z"));
        when(repository.getPixStatement(ACCOUNT_ID, 20, 0)).thenReturn(List.of(entry));

        assertThat(service.getPixStatement(ACCOUNT_ID, 20, 0)).containsExactly(entry);
    }

    @Test
    void passesPaginationThroughUnchanged() {
        when(repository.getPixStatement(ACCOUNT_ID, 5, 10)).thenReturn(List.of());

        assertThat(service.getPixStatement(ACCOUNT_ID, 5, 10)).isEmpty();

        verify(repository).getPixStatement(ACCOUNT_ID, 5, 10);
    }

    @Test
    void doesNotConsultTheCacheForStatements() {
        when(repository.getPixStatement(ACCOUNT_ID, 20, 0)).thenReturn(List.of());

        service.getPixStatement(ACCOUNT_ID, 20, 0);

        verifyNoInteractions(balanceCache);
    }
}
