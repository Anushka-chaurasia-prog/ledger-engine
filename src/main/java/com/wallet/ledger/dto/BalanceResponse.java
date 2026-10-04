package com.wallet.ledger.dto;

import java.util.UUID;

public class BalanceResponse {
    private UUID accountId;
    private String currency;
    private Long balance;

    public BalanceResponse() {
    }

    public BalanceResponse(UUID accountId, String currency, Long balance) {
        this.accountId = accountId;
        this.currency = currency;
        this.balance = balance;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }
}
