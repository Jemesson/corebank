package com.corebank.command.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PixPaymentRequest(

        @NotNull(message = "originAccountId is required")
        Long originAccountId,

        @NotBlank(message = "targetPix is required")
        String targetPix,

        @NotNull(message = "value is required")
        @DecimalMin(value = "0.01", message = "value must be greater than zero")
        @Digits(integer = 16, fraction = 2, message = "value accepts at most 2 decimal places")
        BigDecimal value
) {}
