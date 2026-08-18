package com.corebank.command.entity;

import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAmountException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

@Table("accounts")
public class Account {
    @Id
    private Long id;
    @Column("document")
    private String document;
    @Column("total_balance")
    private BigDecimal totalBalance;
    @Column("balance")
    private BigDecimal balance;
    @Version
    @Column("version")
    private Long version;

    public Account() {}

    public Long getId() { return id; }
    public String getDocument() { return document; }
    public BigDecimal getTotalBalance() { return totalBalance; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }

    public void withdraw(BigDecimal value) {
        requirePositive(value);
        requireAvailable(value);
        this.balance = this.balance.subtract(value);
        this.totalBalance = this.totalBalance.subtract(value);
    }

    public void hold(BigDecimal value) {
        requirePositive(value);
        requireAvailable(value);
        this.balance = this.balance.subtract(value);
    }

    public void settleHold(BigDecimal value) {
        requirePositive(value);
        this.totalBalance = this.totalBalance.subtract(value);
    }

    public void releaseHold(BigDecimal value) {
        requirePositive(value);
        this.balance = this.balance.add(value);
    }

    private void requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new InvalidAmountException("Valor deve ser maior que zero, recebido: " + value);
        }
    }

    private void requireAvailable(BigDecimal value) {
        if (this.balance.compareTo(value) < 0) {
            throw new InsufficientBalanceException(this.id, this.balance, value);
        }
    }
}
