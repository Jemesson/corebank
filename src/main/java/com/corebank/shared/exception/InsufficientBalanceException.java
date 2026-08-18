package com.corebank.shared.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(Long accountId, BigDecimal available, BigDecimal requested) {
        super("Insufficient available balance in account " + accountId
                + ": available=" + available + ", requested=" + requested);
    }
}
