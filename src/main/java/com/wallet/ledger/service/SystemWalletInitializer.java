package com.wallet.ledger.service;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.repository.AccountRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SystemWalletInitializer {

    public static final UUID SYSTEM_INR_WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final AccountRepository accountRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SystemWalletInitializer(AccountRepository accountRepository, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initSystemWallet() {
        if (!accountRepository.existsById(SYSTEM_INR_WALLET_ID)) {
            jdbcTemplate.update("INSERT INTO accounts (id, currency, created_at) VALUES (?, ?, NOW()) ON CONFLICT (id) DO NOTHING", 
                SYSTEM_INR_WALLET_ID, "INR");
        }
    }

    public Account getSystemWallet() {
        return accountRepository.findById(SYSTEM_INR_WALLET_ID)
                .orElseThrow(() -> new IllegalStateException("System wallet not initialized"));
    }
}
