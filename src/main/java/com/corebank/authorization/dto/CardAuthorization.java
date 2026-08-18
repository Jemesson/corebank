package com.corebank.authorization.dto;

import java.math.BigDecimal;

public record CardAuthorization(
   String authorizationCode, Long accountId,
   BigDecimal amount, String merchant, String status) {}