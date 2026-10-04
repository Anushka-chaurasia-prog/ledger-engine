package com.wallet.ledger.config;

public final class LedgerConstants {
    // 10 billion INR (in paise: 1 trillion)
    public static final long MAX_TRANSACTION_AMOUNT = 1_000_000_000_000L;
    public static final String MAX_TRANSACTION_AMOUNT_STRING = "1000000000000";

    private LedgerConstants() {
        // Prevent instantiation
    }
}
