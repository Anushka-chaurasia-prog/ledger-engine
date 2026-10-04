package com.wallet.ledger.controller;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.LedgerEntry;
import com.wallet.ledger.domain.LedgerTransaction;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class TransferControllerTest {

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

    @BeforeEach
    void setUp() throws Exception {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // Re-initialize system wallet since we wiped the DB
        systemWalletInitializer.initSystemWallet();

        // Create accounts
        Account a = accountRepository.save(new Account("INR"));
        Account b = accountRepository.save(new Account("INR"));
        accountA = a.getId();
        accountB = b.getId();

        // Seed account A with 100000
        mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 100000}"))
                .andExpect(status().isCreated());

        // Seed account B with 50000
        mockMvc.perform(post("/api/accounts/" + accountB + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isCreated());
                
        // Clear transaction stats before running transfer tests so we only evaluate transfer transactions
        long txCount = transactionRepository.count();
        assertThat(txCount).isEqualTo(2); // The two deposits
    }

    @Test
    void testSuccessfulTransfer() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":30000}", accountA, accountB);

        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(30000))
                .andExpect(jsonPath("$.status").value("POSTED"));

        // Verify balances
        Long balanceA = entryRepository.getBalanceByAccountId(accountA);
        Long balanceB = entryRepository.getBalanceByAccountId(accountB);

        assertThat(balanceA).isEqualTo(70000); // 100000 - 30000
        assertThat(balanceB).isEqualTo(80000); // 50000 + 30000

        // Verify exactly one TRANSFER transaction exists
        List<LedgerTransaction> txs = transactionRepository.findAll().stream()
                .filter(tx -> tx.getType().name().equals("TRANSFER")).toList();
        assertThat(txs).hasSize(1);
        LedgerTransaction transferTx = txs.get(0);

        // Verify exactly two entries exist for the transfer
        List<LedgerEntry> entries = entryRepository.findAll().stream()
                .filter(e -> e.getTransaction().getId().equals(transferTx.getId()))
                .toList();
        assertThat(entries).hasSize(2);

        // Verify sender and receiver amounts
        LedgerEntry senderEntry = entries.stream().filter(e -> e.getAmount() < 0).findFirst().get();
        LedgerEntry receiverEntry = entries.stream().filter(e -> e.getAmount() > 0).findFirst().get();

        assertThat(senderEntry.getAmount()).isEqualTo(-30000);
        assertThat(senderEntry.getAccount().getId()).isEqualTo(accountA);

        assertThat(receiverEntry.getAmount()).isEqualTo(30000);
        assertThat(receiverEntry.getAccount().getId()).isEqualTo(accountB);

        // Verify entry sum = 0
        long sum = entries.stream().mapToLong(LedgerEntry::getAmount).sum();
        assertThat(sum).isEqualTo(0);

        // Verify outbox event exists
        long outboxCount = outboxEventRepository.findAll().stream()
                .filter(o -> o.getTransaction().getId().equals(transferTx.getId()))
                .count();
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void testInsufficientFunds() throws Exception {
        // Create an account with 10000 balance
        Account c = accountRepository.save(new Account("INR"));
        UUID accountC = c.getId();
        mockMvc.perform(post("/api/accounts/" + accountC + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10000}"))
                .andExpect(status().isCreated());

        // Count DB state before transfer
        long txCountBefore = transactionRepository.count();
        long entryCountBefore = entryRepository.count();
        long outboxCountBefore = outboxEventRepository.count();

        // Attempt transfer of 20000
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":20000}", accountC, accountB);

        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest()); // Expect 400

        // Verify DB state unchanged
        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
        assertThat(entryRepository.count()).isEqualTo(entryCountBefore);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountBefore);
    }

    @Test
    void testSameAccountTransferFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accountA, accountA);
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testNonexistentSenderFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", UUID.randomUUID(), accountB);
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    void testNonexistentReceiverFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accountA, UUID.randomUUID());
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    void testConcurrencyDeadlockPrevention() throws Exception {
        // Run opposite transfers simultaneously (A->B, B->A)
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final boolean direction = i % 2 == 0;
            executor.submit(() -> {
                try {
                    latch.await();
                    
                    String from = direction ? accountA.toString() : accountB.toString();
                    String to = direction ? accountB.toString() : accountA.toString();
                    String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10}", from, to);

                    mockMvc.perform(post("/api/transfers")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                            .andExpect(status().isCreated());
                            
                    successCount.incrementAndGet();
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
        assertThat(successCount.get()).isEqualTo(threads);
        
        // Final balances:
        // Initial: A=100000, B=50000
        // A transferred 10 to B (5 times) = -50
        // B transferred 10 to A (5 times) = +50
        // Net change should be 0.
        Long balanceA = entryRepository.getBalanceByAccountId(accountA);
        Long balanceB = entryRepository.getBalanceByAccountId(accountB);

        assertThat(balanceA).isEqualTo(100000);
        assertThat(balanceB).isEqualTo(50000);

        executor.shutdown();
    }

    @Test
    void testIdempotentTransfer_SameRequest() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":500}", accountA, accountB);
        String idempotencyKey = "idemp-same-req-123";

        // First request
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated());

        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();
        long outboxCount = outboxEventRepository.count();

        // Second request - identical
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated());

        // Verify counts remain identical
        assertThat(transactionRepository.count()).isEqualTo(txCount);
        assertThat(entryRepository.count()).isEqualTo(entryCount);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
    }

    @Test
    void testIdempotentTransfer_DifferentRequest() throws Exception {
        String request1 = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":50000}", accountA, accountB);
        String request2 = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":70000}", accountA, accountB);
        String idempotencyKey = "idemp-diff-req-123";

        // First request
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request1))
                .andExpect(status().isCreated());

        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();

        // Second request - different amount
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request2))
                .andExpect(status().isConflict());

        // Verify counts remain identical
        assertThat(transactionRepository.count()).isEqualTo(txCount);
        assertThat(entryRepository.count()).isEqualTo(entryCount);
        
        Long balanceA = entryRepository.getBalanceByAccountId(accountA);
        assertThat(balanceA).isEqualTo(50000); // 100000 - 50000
    }

    @Test
    void testConcurrentDuplicateRequests() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger createdCount = new AtomicInteger(0);

        String idempotencyKey = "concurrent-idemp-123";
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":20000}", accountA, accountB);

        long txCountBefore = transactionRepository.count();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    mockMvc.perform(post("/api/transfers")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                            .andExpect(status().isCreated());
                    createdCount.incrementAndGet();
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
        assertThat(createdCount.get()).isEqualTo(threads);

        // Verify only one transaction was actually created
        assertThat(transactionRepository.count()).isEqualTo(txCountBefore + 1);

        executor.shutdown();
    }

    @Test
    void testMissingIdempotencyKeyFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accountA, accountB);
        mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testBlankIdempotencyKeyFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accountA, accountB);
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testTooLongIdempotencyKeyFails() throws Exception {
        String request = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accountA, accountB);
        String longKey = "a".repeat(256);
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", longKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest());
    }
}
