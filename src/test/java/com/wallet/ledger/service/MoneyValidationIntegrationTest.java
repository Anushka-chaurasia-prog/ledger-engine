package com.wallet.ledger.service;

import com.wallet.ledger.config.LedgerConstants;
import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.LedgerEntry;
import com.wallet.ledger.domain.LedgerTransaction;
import com.wallet.ledger.domain.TransactionStatus;
import com.wallet.ledger.domain.TransactionType;
import com.wallet.ledger.dto.DepositRequest;
import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.repository.LedgerEntryRepository;
import com.wallet.ledger.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "scheduler.enabled=false"
})
public class MoneyValidationIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;
    
    @Autowired
    private com.wallet.ledger.repository.OutboxEventRepository outboxEventRepository;

    @Autowired
    private SystemWalletInitializer systemWalletInitializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        systemWalletInitializer.initSystemWallet();
    }

    @Test
    void testNegativeDeposit_Rejected() {
        Account account = accountRepository.save(new Account("INR"));
        
        long initialTxCount = transactionRepository.count();
        long initialEntryCount = entryRepository.count();
        long initialOutboxCount = outboxEventRepository.count();
        
        assertThatThrownBy(() -> depositService.deposit(account.getId(), new DepositRequest(-100L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount must be greater than 0");
                
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(entryRepository.count()).isEqualTo(initialEntryCount);
        assertThat(outboxEventRepository.count()).isEqualTo(initialOutboxCount);
    }

    @Test
    void testZeroDeposit_Rejected() {
        Account account = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> depositService.deposit(account.getId(), new DepositRequest(0L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount must be greater than 0");
    }

    @Test
    void testNegativeTransfer_Rejected() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        
        long initialTxCount = transactionRepository.count();
        long initialEntryCount = entryRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(from.getId(), to.getId(), -500L), UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount must be greater than 0");
                
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(entryRepository.count()).isEqualTo(initialEntryCount);
        assertThat(outboxEventRepository.count()).isEqualTo(initialOutboxCount);
    }

    @Test
    void testZeroTransfer_Rejected() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(from.getId(), to.getId(), 0L), UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount must be greater than 0");
    }

    @Test
    void testAmountAboveMaximum_Rejected() {
        Account account = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> depositService.deposit(account.getId(), new DepositRequest(LedgerConstants.MAX_TRANSACTION_AMOUNT + 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount exceeds maximum limit");
    }

    @Test
    void testLongMaxValue_Rejected() {
        Account account = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> depositService.deposit(account.getId(), new DepositRequest(Long.MAX_VALUE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount exceeds maximum limit");
    }

    @Test
    void testLongMinValue_Rejected() {
        Account account = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> depositService.deposit(account.getId(), new DepositRequest(Long.MIN_VALUE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Amount must be greater than 0");
    }

    @Test
    void testInsufficientFunds_Rejected() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        depositService.deposit(from.getId(), new DepositRequest(500L));
        
        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(from.getId(), to.getId(), 1000L), UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void testSameAccountTransfer_Rejected() {
        Account from = accountRepository.save(new Account("INR"));
        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(from.getId(), from.getId(), 500L), UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot transfer to the same account");
    }

    @Test
    void testValidMaximumBoundaryAmount() {
        Account account = accountRepository.save(new Account("INR"));
        depositService.deposit(account.getId(), new DepositRequest(LedgerConstants.MAX_TRANSACTION_AMOUNT));
        
        Long balance = entryRepository.getBalanceByAccountId(account.getId());
        assertThat(balance).isEqualTo(LedgerConstants.MAX_TRANSACTION_AMOUNT);
    }

    @Test
    void testDatabaseRejectionOfZeroLedgerEntry() {
        Account account = accountRepository.save(new Account("INR"));
        Account sys = systemWalletInitializer.getSystemWallet();
        
        assertThatThrownBy(() -> {
            transactionTemplate.execute(status -> {
                LedgerTransaction tx = new LedgerTransaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, UUID.randomUUID().toString());
                tx = transactionRepository.save(tx);
                
                // We create a mathematically balanced transaction, but with 0 amount.
                LedgerEntry e1 = new LedgerEntry(tx, account, 0L, "INR");
                LedgerEntry e2 = new LedgerEntry(tx, sys, 0L, "INR");
                
                entryRepository.save(e1);
                entryRepository.save(e2);
                
                return null;
            });
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("check_amount_non_zero");
    }
}
