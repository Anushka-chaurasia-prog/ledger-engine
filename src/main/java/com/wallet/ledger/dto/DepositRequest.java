package com.wallet.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class DepositRequest {
    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be greater than 0")
    @jakarta.validation.constraints.Max(value = com.wallet.ledger.config.LedgerConstants.MAX_TRANSACTION_AMOUNT, message = "Amount exceeds maximum limit")
    private Long amount;

    public DepositRequest() {}
    
    public DepositRequest(Long amount) {
        this.amount = amount;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }
}
