package com.wallet.ledger.controller;

import com.wallet.ledger.dto.AccountResponse;
import com.wallet.ledger.dto.CreateAccountRequest;
import com.wallet.ledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.wallet.ledger.service.AccountQueryService;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountQueryService accountQueryService;

    public AccountController(AccountService accountService, AccountQueryService accountQueryService) {
        this.accountService = accountService;
        this.accountQueryService = accountQueryService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<com.wallet.ledger.dto.BalanceResponse> getBalance(@PathVariable java.util.UUID accountId) {
        return ResponseEntity.ok(accountQueryService.getBalance(accountId));
    }

    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<org.springframework.data.domain.Page<com.wallet.ledger.dto.TransactionHistoryDto>> getTransactionHistory(
            @PathVariable java.util.UUID accountId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        }
        return ResponseEntity.ok(accountQueryService.getTransactionHistory(accountId, page, size));
    }
}
