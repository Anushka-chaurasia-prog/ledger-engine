package com.wallet.ledger.service;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.dto.BalanceResponse;
import com.wallet.ledger.dto.TransactionHistoryDto;
import com.wallet.ledger.exception.AccountNotFoundException;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.repository.LedgerEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountQueryService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public BalanceResponse getBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

        Long balance = ledgerEntryRepository.getBalanceByAccountId(accountId);

        return new BalanceResponse(account.getId(), account.getCurrency(), balance);
    }

    public Page<TransactionHistoryDto> getTransactionHistory(UUID accountId, int page, int size) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException("Account not found");
        }

        if (size > 100) {
            size = 100; // Cap at 100
        }
        
        Pageable pageable = PageRequest.of(page, size);
        return ledgerEntryRepository.findTransactionHistoryByAccountId(accountId, pageable);
    }
}
