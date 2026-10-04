package com.wallet.ledger.dto;

import com.wallet.ledger.domain.TransactionStatus;
import com.wallet.ledger.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public class TransactionHistoryDto {
    private UUID transactionId;
    private TransactionType type;
    private TransactionStatus status;
    private Long amount;
    private String direction;
    private UUID counterpartyAccountId;
    private String currency;
    private Instant createdAt;

    public TransactionHistoryDto(UUID transactionId, TransactionType type, TransactionStatus status, Long signedAmount, UUID counterpartyAccountId, String currency, Instant createdAt) {
        this.transactionId = transactionId;
        this.type = type;
        this.status = status;
        this.direction = signedAmount < 0 ? "DEBIT" : "CREDIT";
        this.amount = Math.abs(signedAmount);
        this.counterpartyAccountId = counterpartyAccountId;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Long getAmount() {
        return amount;
    }

    public String getDirection() {
        return direction;
    }

    public UUID getCounterpartyAccountId() {
        return counterpartyAccountId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TransactionHistoryDto() {
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public void setCounterpartyAccountId(UUID counterpartyAccountId) {
        this.counterpartyAccountId = counterpartyAccountId;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
