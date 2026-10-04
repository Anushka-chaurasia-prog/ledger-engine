package com.wallet.ledger.dto;

import java.time.Instant;
import java.util.UUID;

public class AccountResponse {
    private UUID id;
    private String currency;
    private Instant createdAt;

    public AccountResponse(UUID id, String currency, Instant createdAt) {
        this.id = id;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
