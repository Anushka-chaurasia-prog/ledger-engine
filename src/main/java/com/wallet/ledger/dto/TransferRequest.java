package com.wallet.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class TransferRequest {

    @NotNull(message = "fromAccountId is required")
    private UUID fromAccountId;

    @NotNull(message = "toAccountId is required")
    private UUID toAccountId;

    @NotNull(message = "amount is required")
    @Min(value = 1, message = "Amount must be greater than 0")
    @jakarta.validation.constraints.Max(value = com.wallet.ledger.config.LedgerConstants.MAX_TRANSACTION_AMOUNT, message = "Amount exceeds maximum limit")
    private Long amount;

    public TransferRequest() {}

    public TransferRequest(UUID fromAccountId, UUID toAccountId, Long amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(UUID fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(UUID toAccountId) {
        this.toAccountId = toAccountId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }
}
