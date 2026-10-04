package com.wallet.ledger.service;

import com.wallet.ledger.domain.*;
import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.dto.TransferResponse;
import com.wallet.ledger.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxEventRepository outboxEventRepository;

    public TransferService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            OutboxEventRepository outboxEventRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    public Optional<TransferResponse> getExistingTransfer(String idempotencyKey, TransferRequest request) {
        Optional<LedgerTransaction> txOpt = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (txOpt.isEmpty()) {
            return Optional.empty();
        }
        LedgerTransaction tx = txOpt.get();
        if (tx.getType() != TransactionType.TRANSFER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used with a different request type");
        }

        java.util.List<LedgerEntry> entries = entryRepository.findByTransactionId(tx.getId());
        UUID from = null;
        UUID to = null;
        Long amount = null;

        for (LedgerEntry e : entries) {
            if (e.getAmount() < 0) {
                from = e.getAccount().getId();
                amount = -e.getAmount();
            } else {
                to = e.getAccount().getId();
            }
        }

        if (!request.getFromAccountId().equals(from) || !request.getToAccountId().equals(to) || !request.getAmount().equals(amount)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used with a different request");
        }

        return Optional.of(new TransferResponse(tx.getId(), from, to, amount, "INR", tx.getStatus().name()));
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        UUID fromId = request.getFromAccountId();
        UUID toId = request.getToAccountId();
        Long amount = request.getAmount();

        if (fromId.equals(toId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer to the same account");
        }

        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than 0");
        }
        if (amount > com.wallet.ledger.config.LedgerConstants.MAX_TRANSACTION_AMOUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount exceeds maximum limit");
        }

        // 1. Lock accounts deterministically to prevent deadlocks
        UUID lo = fromId.compareTo(toId) < 0 ? fromId : toId;
        UUID hi = fromId.compareTo(toId) < 0 ? toId : fromId;

        Account loAccount = accountRepository.findByIdForUpdate(lo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + lo));
        Account hiAccount = accountRepository.findByIdForUpdate(hi)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + hi));

        Account fromAccount = fromId.equals(lo) ? loAccount : hiAccount;
        Account toAccount = toId.equals(lo) ? loAccount : hiAccount;

        // 2. Validate currencies
        if (!"INR".equals(fromAccount.getCurrency()) || !"INR".equals(toAccount.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported currency. Only INR is supported.");
        }

        // 3. Check balance for sender
        Long senderBalance = entryRepository.getBalanceByAccountId(fromId);
        if (senderBalance < amount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient funds");
        }

        // 4. Create LedgerTransaction
        LedgerTransaction transaction = new LedgerTransaction(TransactionType.TRANSFER, TransactionStatus.POSTED, idempotencyKey);
        transaction = transactionRepository.saveAndFlush(transaction);

        // 5. Create LedgerEntries
        LedgerEntry senderEntry = new LedgerEntry(transaction, fromAccount, -amount, "INR");
        LedgerEntry receiverEntry = new LedgerEntry(transaction, toAccount, amount, "INR");

        entryRepository.save(senderEntry);
        entryRepository.save(receiverEntry);

        // 6. Create OutboxEvent
        String payload = String.format("{\"transactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%d}",
                transaction.getId(), fromId, toId, amount);
        OutboxEvent outboxEvent = new OutboxEvent(transaction, "TRANSFER_COMPLETED", payload, OutboxStatus.PENDING);
        outboxEventRepository.save(outboxEvent);

        return new TransferResponse(
                transaction.getId(),
                fromId,
                toId,
                amount,
                "INR",
                transaction.getStatus().name()
        );
    }
}
