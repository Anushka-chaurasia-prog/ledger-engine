package com.wallet.ledger.service;

import com.wallet.ledger.domain.*;
import com.wallet.ledger.dto.DepositRequest;
import com.wallet.ledger.dto.DepositResponse;
import com.wallet.ledger.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DepositService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SystemWalletInitializer systemWalletInitializer;

    public DepositService(
            AccountRepository accountRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            OutboxEventRepository outboxEventRepository,
            SystemWalletInitializer systemWalletInitializer) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.systemWalletInitializer = systemWalletInitializer;
    }

    public Optional<DepositResponse> getExistingDeposit(String idempotencyKey, UUID accountId, DepositRequest request) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        Optional<LedgerTransaction> txOpt = ledgerTransactionRepository.findByIdempotencyKey(idempotencyKey);
        if (txOpt.isEmpty()) {
            return Optional.empty();
        }
        LedgerTransaction tx = txOpt.get();
        if (tx.getType() != TransactionType.DEPOSIT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used with a different request type");
        }

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(tx.getId());
        LedgerEntry customerEntry = entries.stream()
                .filter(e -> e.getAmount() > 0)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid deposit entries"));

        if (!customerEntry.getAccount().getId().equals(accountId) || !customerEntry.getAmount().equals(request.getAmount())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used with a different request");
        }

        Long balance = ledgerEntryRepository.getBalanceByAccountId(accountId);

        return Optional.of(new DepositResponse(
                tx.getId(),
                customerEntry.getAccount().getId(),
                customerEntry.getAmount(),
                customerEntry.getCurrency(),
                tx.getStatus().name(),
                balance
        ));
    }

    @Transactional
    public DepositResponse deposit(UUID accountId, DepositRequest request, String idempotencyKey) {
        Account customerAccount = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!"INR".equals(customerAccount.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported currency for deposit");
        }

        Long amount = request.getAmount();
        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than 0");
        }
        if (amount > com.wallet.ledger.config.LedgerConstants.MAX_TRANSACTION_AMOUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount exceeds maximum limit");
        }

        Account systemAccount = systemWalletInitializer.getSystemWallet();

        LedgerTransaction transaction = new LedgerTransaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, idempotencyKey);
        transaction = ledgerTransactionRepository.saveAndFlush(transaction);

        LedgerEntry systemEntry = new LedgerEntry(transaction, systemAccount, -request.getAmount(), "INR");
        LedgerEntry customerEntry = new LedgerEntry(transaction, customerAccount, request.getAmount(), "INR");

        ledgerEntryRepository.save(systemEntry);
        ledgerEntryRepository.save(customerEntry);

        // Create Outbox Event in the same transaction
        String payload = String.format("{\"transactionId\":\"%s\",\"accountId\":\"%s\",\"amount\":%d}", 
            transaction.getId(), accountId, request.getAmount());
        OutboxEvent outboxEvent = new OutboxEvent(transaction, "DEPOSIT_COMPLETED", payload, OutboxStatus.PENDING);
        outboxEventRepository.save(outboxEvent);

        // Query final balance after inserts
        Long balance = ledgerEntryRepository.getBalanceByAccountId(accountId);

        return new DepositResponse(
                transaction.getId(),
                customerAccount.getId(),
                request.getAmount(),
                "INR",
                transaction.getStatus().name(),
                balance
        );
    }

    @Transactional
    public DepositResponse deposit(UUID accountId, DepositRequest request) {
        return deposit(accountId, request, null);
    }
}
