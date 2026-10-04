package com.wallet.ledger.controller;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.LedgerEntry;
import com.wallet.ledger.domain.LedgerTransaction;
import com.wallet.ledger.domain.TransactionType;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.repository.LedgerEntryRepository;
import com.wallet.ledger.repository.LedgerTransactionRepository;
import com.wallet.ledger.repository.OutboxEventRepository;
import com.wallet.ledger.service.SystemWalletInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ReversalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SystemWalletInitializer systemWalletInitializer;

    private UUID accountA;
    private UUID accountB;
    private UUID transferTxId;

    @BeforeEach
    void setUp() throws Exception {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        systemWalletInitializer.initSystemWallet();

        // Create accounts
        Account a = accountRepository.save(new Account("INR"));
        Account b = accountRepository.save(new Account("INR"));
        accountA = a.getId();
        accountB = b.getId();

        // Deposit 100000 into A
        mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 100000}"))
                .andExpect(status().isCreated());

        // Deposit 50000 into B
        mockMvc.perform(post("/api/accounts/" + accountB + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isCreated());

        // Transfer 30000 from A to B
        String transferReq = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":30000}", accountA, accountB);
        MvcResult res = mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferReq))
                .andExpect(status().isCreated())
                .andReturn();
        
        String resBody = res.getResponse().getContentAsString();
        String txIdStr = JsonPath.read(resBody, "$.transactionId");
        transferTxId = UUID.fromString(txIdStr);
    }

    @Test
    void testSuccessfulReversal() throws Exception {
        // Balances before reversal: A = 70000, B = 80000
        mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalTransactionId").value(transferTxId.toString()))
                .andExpect(jsonPath("$.amount").value(30000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("POSTED"));

        // Expected final balances: A = 100000, B = 50000
        assertThat(entryRepository.getBalanceByAccountId(accountA)).isEqualTo(100000);
        assertThat(entryRepository.getBalanceByAccountId(accountB)).isEqualTo(50000);

        // Verify original tx unchanged
        LedgerTransaction originalTx = transactionRepository.findById(transferTxId).get();
        assertThat(originalTx.getType()).isEqualTo(TransactionType.TRANSFER);

        // Find reversal tx
        LedgerTransaction reversalTx = transactionRepository.findByReversesTx(originalTx).get();
        assertThat(reversalTx.getType()).isEqualTo(TransactionType.REVERSAL);

        // Reversal entries
        List<LedgerEntry> reversalEntries = entryRepository.findByTransactionId(reversalTx.getId());
        assertThat(reversalEntries).hasSize(2);
        
        long sum = reversalEntries.stream().mapToLong(LedgerEntry::getAmount).sum();
        assertThat(sum).isEqualTo(0);

        LedgerEntry entryForB = reversalEntries.stream().filter(e -> e.getAccount().getId().equals(accountB)).findFirst().get();
        LedgerEntry entryForA = reversalEntries.stream().filter(e -> e.getAccount().getId().equals(accountA)).findFirst().get();

        assertThat(entryForB.getAmount()).isEqualTo(-30000);
        assertThat(entryForA.getAmount()).isEqualTo(30000);

        // Verify outbox
        long outboxCount = outboxEventRepository.findAll().stream()
                .filter(o -> o.getTransaction().getId().equals(reversalTx.getId()) && o.getEventType().equals("TRANSFER_REVERSED"))
                .count();
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void testOriginalTransactionRemainsImmutable() throws Exception {
        // Fetch original entries before
        List<LedgerEntry> originalEntriesBefore = entryRepository.findByTransactionId(transferTxId);

        mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isCreated());

        // Fetch original entries after
        List<LedgerEntry> originalEntriesAfter = entryRepository.findByTransactionId(transferTxId);

        assertThat(originalEntriesBefore.size()).isEqualTo(originalEntriesAfter.size());
        for (int i = 0; i < originalEntriesBefore.size(); i++) {
            assertThat(originalEntriesBefore.get(i).getAmount()).isEqualTo(originalEntriesAfter.get(i).getAmount());
            assertThat(originalEntriesBefore.get(i).getAccount().getId()).isEqualTo(originalEntriesAfter.get(i).getAccount().getId());
        }
    }

    @Test
    void testDoubleReversal() throws Exception {
        // First reversal
        mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isCreated());

        // Second reversal attempts
        mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isConflict()); // HTTP 409

        // Verify only one reversal transaction exists
        LedgerTransaction originalTx = transactionRepository.findById(transferTxId).get();
        long reversalCount = transactionRepository.findAll().stream()
                .filter(tx -> tx.getReversesTx() != null && originalTx.getId().equals(tx.getReversesTx().getId()))
                .count();
        assertThat(reversalCount).isEqualTo(1);
    }

    @Test
    void testConcurrentDuplicateReversal() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        // Snapshot total entry count BEFORE the concurrent race begins.
        // After the race, the table must contain exactly 2 more entries (the winning
        // reversal's debit + credit). Any partial entries from losing threads prove
        // a rollback failure.
        long entryCountBefore = entryRepository.count();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse")).andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        createdCount.incrementAndGet();
                    } else if (res.getResponse().getStatus() == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        boolean completed = done.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);

        // Verify DB integrity: exactly one reversal transaction
        LedgerTransaction originalTx = transactionRepository.findById(transferTxId).get();
        List<LedgerTransaction> reversals = transactionRepository.findAll().stream()
                .filter(tx -> tx.getReversesTx() != null && originalTx.getId().equals(tx.getReversesTx().getId()))
                .toList();
        
        assertThat(reversals).hasSize(1);
        LedgerTransaction theReversal = reversals.get(0);
        assertThat(entryRepository.findByTransactionId(theReversal.getId())).hasSize(2);

        // === Test 3 gap: prove losing threads left zero partial entries ===
        // Total entries must be exactly entryCountBefore + 2 (the one winning reversal's entries).
        // Any deviation means a rolled-back transaction leaked a committed row.
        long entryCountAfter = entryRepository.count();
        assertThat(entryCountAfter)
                .as("Losing reversal attempts must not leave any partial ledger entries")
                .isEqualTo(entryCountBefore + 2);
        
        executor.shutdown();
    }


    @Test
    void testInsufficientFundsForReversal() throws Exception {
        // A -> B 30000. Balance: A=70000, B=80000.
        // We will now empty B. B -> A 80000.
        String emptyBReq = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":80000}", accountB, accountA);
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyBReq))
                .andExpect(status().isCreated());

        // B has 0. Attempting to reverse original tx (which requires B to send 30000) should fail.
        mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isBadRequest()); // Insufficient funds

        // Verify no reversal tx was created
        LedgerTransaction originalTx = transactionRepository.findById(transferTxId).get();
        assertThat(transactionRepository.findByReversesTx(originalTx)).isEmpty();
    }

    @Test
    void testNonexistentTransaction() throws Exception {
        mockMvc.perform(post("/api/transfers/" + UUID.randomUUID() + "/reverse"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testNonTransferTransaction() throws Exception {
        // Find a deposit tx
        LedgerTransaction depositTx = transactionRepository.findAll().stream()
                .filter(tx -> tx.getType() == TransactionType.DEPOSIT)
                .findFirst().get();

        mockMvc.perform(post("/api/transfers/" + depositTx.getId() + "/reverse"))
                .andExpect(status().isConflict()); // Or BadRequest, we used CONFLICT
    }

    @Test
    void testReversalOfReversal() throws Exception {
        // First reversal
        MvcResult res = mockMvc.perform(post("/api/transfers/" + transferTxId + "/reverse"))
                .andExpect(status().isCreated())
                .andReturn();

        String resBody = res.getResponse().getContentAsString();
        String revTxIdStr = JsonPath.read(resBody, "$.reversalTransactionId");

        // Reverse the reversal
        mockMvc.perform(post("/api/transfers/" + revTxIdStr + "/reverse"))
                .andExpect(status().isConflict()); // Only TRANSFER transactions can be reversed
    }
}
