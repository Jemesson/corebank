package com.corebank.command.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("pix_transactions")
public class PixTransaction {
    @Id
    private UUID id;
    @Column("end_to_end_id")
    private String endToEndId;
    @Column("origin_account_id")
    private Long originAccountId;
    @Column("target_pix_key")
    private String targetPix;
    private BigDecimal value;
    @Column("operation_type")
    private String type;
    private String status;
    @Column("created_at")
    private Instant createdAt;

    public PixTransaction() {}

    public PixTransaction(String endToEndId, Long originAccountId, String targetPix, BigDecimal value) {
        this.endToEndId = endToEndId;
        this.originAccountId = originAccountId;
        this.targetPix = targetPix;
        this.value = value;
        this.type = "SENT";
        this.status = "COMPLETED";
        this.createdAt = Instant.now();
    }
}
