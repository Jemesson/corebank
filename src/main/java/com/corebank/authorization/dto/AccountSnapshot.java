package com.corebank.authorization.dto;

import java.math.BigDecimal;

public record AccountSnapshot(Long accountId, BigDecimal totalBalance, BigDecimal balance, Long version) {
}
