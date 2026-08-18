package com.corebank.query.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PixStatementReadDTO(String endToEndId, String targetPixKey, BigDecimal value,
    String operationType, String status, Instant createdAt
) {}
