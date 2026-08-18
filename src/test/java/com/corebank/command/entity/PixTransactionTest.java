package com.corebank.command.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PixTransactionTest {

    @Test
    void marksANewTransactionAsASettledOutboundTransfer() {
        var transaction = new PixTransaction("E123", 1001L, "target@pix.com", new BigDecimal("100.00"));

        assertThat(transaction).extracting("endToEndId", "originAccountId", "targetPix",
                        "value", "type", "status")
                .containsExactly("E123", 1001L, "target@pix.com",
                        new BigDecimal("100.00"), "SENT", "COMPLETED");
        assertThat((Instant) ReflectionTestUtils.getField(transaction, "createdAt"))
                .isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void keepsANoArgConstructorForTheMappingLayer() {
        var transaction = new PixTransaction();

        assertThat(ReflectionTestUtils.getField(transaction, "endToEndId")).isNull();
        assertThat(ReflectionTestUtils.getField(transaction, "id")).isNull();
    }
}
