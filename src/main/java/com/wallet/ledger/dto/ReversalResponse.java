package com.wallet.ledger.dto;

import java.util.UUID;

public class ReversalResponse {
    private UUID reversalTransactionId;
    private UUID originalTransactionId;
    private String status;
    private Long amount;
    private String currency;

    public ReversalResponse(UUID reversalTransactionId, UUID originalTransactionId, String status, Long amount, String currency) {
        this.reversalTransactionId = reversalTransactionId;
        this.originalTransactionId = originalTransactionId;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getReversalTransactionId() {
        return reversalTransactionId;
    }

    public UUID getOriginalTransactionId() {
        return originalTransactionId;
    }

    public String getStatus() {
        return status;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }
}
