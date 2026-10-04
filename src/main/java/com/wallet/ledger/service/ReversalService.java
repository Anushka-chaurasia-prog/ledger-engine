package com.wallet.ledger.service;

import com.wallet.ledger.domain.*;
import com.wallet.ledger.dto.ReversalResponse;
import com.wallet.ledger.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReversalService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxEventRepository outboxEventRepository;

    public ReversalService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            OutboxEventRepository outboxEventRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public ReversalResponse reverse(UUID originalTransactionId) {
        // 1. Fetch original transaction
        LedgerTransaction originalTx = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        // 2. Validate original transaction
        if (originalTx.getType() != TransactionType.TRANSFER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only TRANSFER transactions can be reversed");
        }

        if (originalTx.getStatus() != TransactionStatus.POSTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction is not POSTED");
        }

        // 3. Check if already reversed (early check)
        Optional<LedgerTransaction> existingReversal = transactionRepository.findByReversesTx(originalTx);
        if (existingReversal.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction already reversed");
        }

        // 4. Fetch entries of original transaction
        List<LedgerEntry> entries = entryRepository.findByTransactionId(originalTx.getId());
        if (entries.size() != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original transaction must have exactly 2 entries");
        }

        LedgerEntry originalSenderEntry = null;
        LedgerEntry originalReceiverEntry = null;

        for (LedgerEntry e : entries) {
            if (e.getAmount() < 0) {
                if (originalSenderEntry != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction structurally invalid: multiple negative entries");
                }
                originalSenderEntry = e;
            } else if (e.getAmount() > 0) {
                if (originalReceiverEntry != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction structurally invalid: multiple positive entries");
                }
                originalReceiverEntry = e;
            }
        }

        if (originalSenderEntry == null || originalReceiverEntry == null || 
            !originalSenderEntry.getAmount().equals(-originalReceiverEntry.getAmount())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction structurally invalid");
        }

        UUID originalSenderId = originalSenderEntry.getAccount().getId();
        UUID originalReceiverId = originalReceiverEntry.getAccount().getId();
        Long originalAmount = originalReceiverEntry.getAmount(); // Absolute amount
        String currency = originalReceiverEntry.getCurrency();

        // 5. Account locking (deterministic order)
        UUID lo = originalSenderId.compareTo(originalReceiverId) < 0 ? originalSenderId : originalReceiverId;
        UUID hi = originalSenderId.compareTo(originalReceiverId) < 0 ? originalReceiverId : originalSenderId;

        Account loAccount = accountRepository.findByIdForUpdate(lo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + lo));
        Account hiAccount = accountRepository.findByIdForUpdate(hi)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + hi));

        Account newSenderAccount = originalReceiverId.equals(lo) ? loAccount : hiAccount;
        Account newReceiverAccount = originalSenderId.equals(lo) ? loAccount : hiAccount;

        // 6. Check balance for reversal sender (original receiver)
        Long reversalSenderBalance = entryRepository.getBalanceByAccountId(originalReceiverId);
        if (reversalSenderBalance < originalAmount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient funds for reversal");
        }

        // 7. Create Reversal Transaction
        LedgerTransaction reversalTx = new LedgerTransaction(TransactionType.REVERSAL, TransactionStatus.POSTED, null);
        reversalTx.setReversesTx(originalTx);
        // Force flush to trigger DataIntegrityViolationException immediately if racing duplicate reversal
        reversalTx = transactionRepository.saveAndFlush(reversalTx);

        // 8. Create Reversal Entries
        // new sender gets negative amount, new receiver gets positive amount
        LedgerEntry reversalSenderEntry = new LedgerEntry(reversalTx, newSenderAccount, -originalAmount, currency);
        LedgerEntry reversalReceiverEntry = new LedgerEntry(reversalTx, newReceiverAccount, originalAmount, currency);

        entryRepository.save(reversalSenderEntry);
        entryRepository.save(reversalReceiverEntry);

        // 9. Create OutboxEvent
        String payload = String.format("{\"reversalTransactionId\":\"%s\",\"originalTransactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%d}",
                reversalTx.getId(), originalTx.getId(), newSenderAccount.getId(), newReceiverAccount.getId(), originalAmount);
        
        OutboxEvent outboxEvent = new OutboxEvent(reversalTx, "TRANSFER_REVERSED", payload, OutboxStatus.PENDING);
        outboxEventRepository.save(outboxEvent);

        return new ReversalResponse(
                reversalTx.getId(),
                originalTx.getId(),
                reversalTx.getStatus().name(),
                originalAmount,
                currency
        );
    }
}
