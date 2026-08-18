package com.corebank.authorization.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CardAuthorizationRequest(

        @NotNull(message = "accountId is required")
        Long accountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 16, fraction = 2, message = "amount accepts at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "merchant is required")
        String merchant
) {}
