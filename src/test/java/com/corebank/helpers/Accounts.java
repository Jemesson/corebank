package com.corebank.helpers;

import com.corebank.command.entity.Account;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

public final class Accounts {

    private Accounts() {}

    public static Account with(Long id, String totalBalance, String balance) {
        return with(id, totalBalance, balance, 0L);
    }

    public static Account with(Long id, String totalBalance, String balance, Long version) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "document", "12345678901");
        ReflectionTestUtils.setField(account, "totalBalance", new BigDecimal(totalBalance));
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        ReflectionTestUtils.setField(account, "version", version);
        return account;
    }
}
