package com.corebank.authorization.dto;

import java.math.BigDecimal;

public record CardAuthorizationResponse(
        String authorizationCode,
        String status,
        String reason,
        BigDecimal availableBalance) {
}
