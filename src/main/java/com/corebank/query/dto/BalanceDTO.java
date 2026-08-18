package com.corebank.query.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public record BalanceDTO(Long accountId, BigDecimal totalBalance, BigDecimal balance, Long version) implements Serializable {

}
