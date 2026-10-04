package com.wallet.ledger.service;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.dto.AccountResponse;
import com.wallet.ledger.dto.CreateAccountRequest;
import com.wallet.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(request.getCurrency());
        account = accountRepository.save(account);
        
        return new AccountResponse(
                account.getId(),
                account.getCurrency(),
                account.getCreatedAt()
        );
    }
}
