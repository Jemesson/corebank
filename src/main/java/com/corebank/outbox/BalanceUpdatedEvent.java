package com.corebank.outbox;

import java.math.BigDecimal;

public record BalanceUpdatedEvent(
        Long accountId,
        BigDecimal totalBalance,
        BigDecimal balance,
        Long version) {
}
