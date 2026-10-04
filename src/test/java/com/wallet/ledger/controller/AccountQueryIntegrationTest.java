package com.wallet.ledger.controller;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.dto.DepositRequest;
import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.service.DepositService;
import com.wallet.ledger.service.ReversalService;
import com.wallet.ledger.service.SystemWalletInitializer;
import com.wallet.ledger.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "scheduler.enabled=false"
})
public class AccountQueryIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SystemWalletInitializer systemWalletInitializer;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReversalService reversalService;

    @BeforeEach
    void setUp() {
        systemWalletInitializer.initSystemWallet();
    }

    @Test
    void test1_AccountWithNoTransactions_BalanceIsZero() {
        Account account = accountRepository.save(new Account("INR"));
        
        ResponseEntity<com.wallet.ledger.dto.BalanceResponse> response = restTemplate.getForEntity(
                "/api/accounts/" + account.getId() + "/balance", com.wallet.ledger.dto.BalanceResponse.class);
                
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBalance()).isEqualTo(0L);
    }

    @Test
    void test2_Deposit_BalanceUpdated() {
        Account account = accountRepository.save(new Account("INR"));
        depositService.deposit(account.getId(), new DepositRequest(100000L));
        
        ResponseEntity<com.wallet.ledger.dto.BalanceResponse> response = restTemplate.getForEntity(
                "/api/accounts/" + account.getId() + "/balance", com.wallet.ledger.dto.BalanceResponse.class);
                
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBalance()).isEqualTo(100000L);
    }

    @Test
    void test3_DepositAndTransferOut_BalanceUpdated() throws Exception {
        Account accountA = accountRepository.save(new Account("INR"));
        Account accountB = accountRepository.save(new Account("INR"));
        
        depositService.deposit(accountA.getId(), new DepositRequest(100000L));
        transferService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), 30000L), UUID.randomUUID().toString());
        
        ResponseEntity<com.wallet.ledger.dto.BalanceResponse> responseA = restTemplate.getForEntity(
                "/api/accounts/" + accountA.getId() + "/balance", com.wallet.ledger.dto.BalanceResponse.class);
        assertThat(responseA.getBody().getBalance()).isEqualTo(70000L);

        ResponseEntity<com.wallet.ledger.dto.BalanceResponse> responseB = restTemplate.getForEntity(
                "/api/accounts/" + accountB.getId() + "/balance", com.wallet.ledger.dto.BalanceResponse.class);
        assertThat(responseB.getBody().getBalance()).isEqualTo(30000L);
    }

    @Test
    void test4_DepositTransferAndReverse_BalanceRestored() throws Exception {
        Account accountA = accountRepository.save(new Account("INR"));
        Account accountB = accountRepository.save(new Account("INR"));
        
        depositService.deposit(accountA.getId(), new DepositRequest(100000L));
        com.wallet.ledger.dto.TransferResponse tx = transferService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), 30000L), UUID.randomUUID().toString());
        
        reversalService.reverse(tx.getTransactionId());
        
        ResponseEntity<com.wallet.ledger.dto.BalanceResponse> response = restTemplate.getForEntity(
                "/api/accounts/" + accountA.getId() + "/balance", com.wallet.ledger.dto.BalanceResponse.class);
                
        assertThat(response.getBody().getBalance()).isEqualTo(100000L);
    }

    @Test
    void test5_NonexistentAccount_Returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/accounts/" + UUID.randomUUID() + "/balance", String.class);
                
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void test6_NewAccountWithNoTransactions_EmptyContent() {
        Account account = accountRepository.save(new Account("INR"));
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> response = restTemplate.exchange(
                "/api/accounts/" + account.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).isEmpty();
    }

    @Test
    void test7_Deposit_ReturnsCreditTransaction() {
        Account account = accountRepository.save(new Account("INR"));
        depositService.deposit(account.getId(), new DepositRequest(15000L));
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> response = restTemplate.exchange(
                "/api/accounts/" + account.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).hasSize(1);
        
        com.wallet.ledger.dto.TransactionHistoryDto tx = response.getBody().getContent().get(0);
        assertThat(tx.getAmount()).isEqualTo(15000L);
        assertThat(tx.getDirection()).isEqualTo("CREDIT");
        assertThat(tx.getType().name()).isEqualTo("DEPOSIT");
        assertThat(tx.getStatus().name()).isEqualTo("POSTED");
        assertThat(tx.getCurrency()).isEqualTo("INR");
    }

    @Test
    void test8_Transfer_A_sees_Debit_B_sees_Credit() throws Exception {
        Account accountA = accountRepository.save(new Account("INR"));
        Account accountB = accountRepository.save(new Account("INR"));
        
        depositService.deposit(accountA.getId(), new DepositRequest(100000L));
        transferService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), 30000L), UUID.randomUUID().toString());
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> responseA = restTemplate.exchange(
                "/api/accounts/" + accountA.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        // A sees DEPOSIT and TRANSFER
        assertThat(responseA.getBody().getContent()).hasSize(2);
        com.wallet.ledger.dto.TransactionHistoryDto txA = responseA.getBody().getContent().get(0); // newest first
        assertThat(txA.getAmount()).isEqualTo(30000L);
        assertThat(txA.getDirection()).isEqualTo("DEBIT");
        assertThat(txA.getType().name()).isEqualTo("TRANSFER");
        assertThat(txA.getCounterpartyAccountId()).isEqualTo(accountB.getId());
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> responseB = restTemplate.exchange(
                "/api/accounts/" + accountB.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        // B sees TRANSFER
        assertThat(responseB.getBody().getContent()).hasSize(1);
        com.wallet.ledger.dto.TransactionHistoryDto txB = responseB.getBody().getContent().get(0);
        assertThat(txB.getAmount()).isEqualTo(30000L);
        assertThat(txB.getDirection()).isEqualTo("CREDIT");
        assertThat(txB.getType().name()).isEqualTo("TRANSFER");
        assertThat(txB.getCounterpartyAccountId()).isEqualTo(accountA.getId());
    }

    @Test
    void test9_TransferAndReversal() throws Exception {
        Account accountA = accountRepository.save(new Account("INR"));
        Account accountB = accountRepository.save(new Account("INR"));
        
        depositService.deposit(accountA.getId(), new DepositRequest(100000L));
        com.wallet.ledger.dto.TransferResponse transfer = transferService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), 30000L), UUID.randomUUID().toString());
        reversalService.reverse(transfer.getTransactionId());
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> responseA = restTemplate.exchange(
                "/api/accounts/" + accountA.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        assertThat(responseA.getBody().getContent()).hasSize(3); // DEPOSIT, TRANSFER, REVERSAL
        com.wallet.ledger.dto.TransactionHistoryDto reversalTx = responseA.getBody().getContent().get(0);
        assertThat(reversalTx.getType().name()).isEqualTo("REVERSAL");
        assertThat(reversalTx.getDirection()).isEqualTo("CREDIT");
        assertThat(reversalTx.getAmount()).isEqualTo(30000L);
        assertThat(reversalTx.getCounterpartyAccountId()).isEqualTo(accountB.getId());
        
        com.wallet.ledger.dto.TransactionHistoryDto originalTx = responseA.getBody().getContent().get(1);
        assertThat(originalTx.getType().name()).isEqualTo("TRANSFER");
        assertThat(originalTx.getDirection()).isEqualTo("DEBIT");
    }

    @Test
    void test10_Pagination() throws Exception {
        Account accountA = accountRepository.save(new Account("INR"));
        Account accountB = accountRepository.save(new Account("INR"));
        depositService.deposit(accountA.getId(), new DepositRequest(1000000L));
        
        for (int i = 0; i < 25; i++) {
            transferService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), 100L), UUID.randomUUID().toString());
        }
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> responsePage0 = restTemplate.exchange(
                "/api/accounts/" + accountB.getId() + "/transactions?page=0&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        assertThat(responsePage0.getBody().getContent()).hasSize(20);
        assertThat(responsePage0.getBody().getTotalElements()).isEqualTo(25);
        assertThat(responsePage0.getBody().getTotalPages()).isEqualTo(2);
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> responsePage1 = restTemplate.exchange(
                "/api/accounts/" + accountB.getId() + "/transactions?page=1&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        assertThat(responsePage1.getBody().getContent()).hasSize(5);
    }

    @Autowired
    private com.wallet.ledger.repository.LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private com.wallet.ledger.repository.LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Test
    void test11_Ordering_ExactSameTimestamp() throws Exception {
        Account account = accountRepository.save(new Account("INR"));
        Account sys = systemWalletInitializer.getSystemWallet();
        
        java.time.Instant now = java.time.Instant.now();
        
        com.wallet.ledger.domain.LedgerEntry[] entries = transactionTemplate.execute(status -> {
            com.wallet.ledger.domain.LedgerTransaction tx1 = new com.wallet.ledger.domain.LedgerTransaction(
                com.wallet.ledger.domain.TransactionType.DEPOSIT, com.wallet.ledger.domain.TransactionStatus.POSTED, UUID.randomUUID().toString());
            org.springframework.test.util.ReflectionTestUtils.setField(tx1, "createdAt", now);
            tx1 = ledgerTransactionRepository.save(tx1);
            
            com.wallet.ledger.domain.LedgerEntry entry1 = new com.wallet.ledger.domain.LedgerEntry(tx1, account, 100L, "INR");
            org.springframework.test.util.ReflectionTestUtils.setField(entry1, "createdAt", now);
            ledgerEntryRepository.save(entry1);

            com.wallet.ledger.domain.LedgerEntry sysEntry1 = new com.wallet.ledger.domain.LedgerEntry(tx1, sys, -100L, "INR");
            org.springframework.test.util.ReflectionTestUtils.setField(sysEntry1, "createdAt", now);
            ledgerEntryRepository.save(sysEntry1);
            
            com.wallet.ledger.domain.LedgerTransaction tx2 = new com.wallet.ledger.domain.LedgerTransaction(
                com.wallet.ledger.domain.TransactionType.DEPOSIT, com.wallet.ledger.domain.TransactionStatus.POSTED, UUID.randomUUID().toString());
            org.springframework.test.util.ReflectionTestUtils.setField(tx2, "createdAt", now);
            tx2 = ledgerTransactionRepository.save(tx2);
            
            com.wallet.ledger.domain.LedgerEntry entry2 = new com.wallet.ledger.domain.LedgerEntry(tx2, account, 200L, "INR");
            org.springframework.test.util.ReflectionTestUtils.setField(entry2, "createdAt", now);
            ledgerEntryRepository.save(entry2);

            com.wallet.ledger.domain.LedgerEntry sysEntry2 = new com.wallet.ledger.domain.LedgerEntry(tx2, sys, -200L, "INR");
            org.springframework.test.util.ReflectionTestUtils.setField(sysEntry2, "createdAt", now);
            ledgerEntryRepository.save(sysEntry2);
            
            return new com.wallet.ledger.domain.LedgerEntry[]{entry1, entry2};
        });
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> response = restTemplate.exchange(
                "/api/accounts/" + account.getId() + "/transactions",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        UUID id1 = entries[0].getId();
        UUID id2 = entries[1].getId();
        
        Long expectedFirstAmount = id1.toString().compareTo(id2.toString()) > 0 ? 100L : 200L;
        Long expectedSecondAmount = id1.toString().compareTo(id2.toString()) > 0 ? 200L : 100L;
        
        assertThat(response.getBody().getContent().get(0).getAmount()).isEqualTo(expectedFirstAmount);
        assertThat(response.getBody().getContent().get(1).getAmount()).isEqualTo(expectedSecondAmount);
    }

    @Test
    void test12_PageSizeLimit() {
        Account account = accountRepository.save(new Account("INR"));
        
        ResponseEntity<com.wallet.ledger.dto.RestPageImpl<com.wallet.ledger.dto.TransactionHistoryDto>> response = restTemplate.exchange(
                "/api/accounts/" + account.getId() + "/transactions?size=101",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
                
        // Size should be capped at 100
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Assuming no transactions, just check the size of the page config
        assertThat(response.getBody().getSize()).isEqualTo(100);
    }
}
