package com.wallet.ledger.repository;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.LedgerEntry;
import com.wallet.ledger.domain.LedgerTransaction;
import com.wallet.ledger.domain.TransactionStatus;
import com.wallet.ledger.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
public class DatabaseInvariantTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void databaseMustRejectUnbalancedTransaction() {
        // 1. Setup an account (outside the main transaction block to ensure it exists)
        Account account = transactionTemplate.execute(status -> {
            Account acc = new Account("INR");
            return accountRepository.save(acc);
        });

        // Generate a random UUID to look up the transaction later
        UUID txId = UUID.randomUUID();

        // 2. Attempt to create an unbalanced transaction inside a TransactionTemplate block
        Throwable thrown = catchThrowable(() -> {
            transactionTemplate.execute(status -> {
                // Create a LedgerTransaction with type DEPOSIT and status POSTED
                LedgerTransaction transaction = new LedgerTransaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, null);
                transaction = transactionRepository.save(transaction);

                // Persist exactly ONE LedgerEntry (+50000)
                LedgerEntry entry = new LedgerEntry(transaction, account, 50000L, "INR");
                entryRepository.save(entry);

                // DO NOT create the balancing -50000 entry.
                // When this block finishes, Spring attempts to commit the transaction.
                // The PostgreSQL DEFERRABLE INITIALLY DEFERRED trigger should fire AT COMMIT.
                return null;
            });
        });

        // 6. Verify that PostgreSQL rejects the transaction
        // Spring usually wraps the commit exception in a TransactionSystemException or DataIntegrityViolationException
        assertThat(thrown).isNotNull();
        assertThat(thrown).isInstanceOf(Exception.class);
        
        // Let's verify the message contains something about the trigger or transaction exception
        System.out.println("Exception thrown by database: " + thrown.getMessage());
        if (thrown.getCause() != null) {
            System.out.println("Cause: " + thrown.getCause().getMessage());
        }

        // 7. Verify that the transaction and its entry do NOT remain persisted
        // The rollback should have cleared them out.
        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();

        assertThat(txCount).isEqualTo(0);
        assertThat(entryCount).isEqualTo(0);
    }
}
