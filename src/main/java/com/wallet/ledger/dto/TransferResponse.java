package com.wallet.ledger.dto;

import java.util.UUID;

public class TransferResponse {
    private UUID transactionId;
    private UUID fromAccountId;
    private UUID toAccountId;
    private Long amount;
    private String currency;
    private String status;

    public TransferResponse(UUID transactionId, UUID fromAccountId, UUID toAccountId, Long amount, String currency, String status) {
        this.transactionId = transactionId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }
}
