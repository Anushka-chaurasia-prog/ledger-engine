package com.wallet.ledger.controller;

import com.wallet.ledger.domain.Account;
import com.wallet.ledger.domain.LedgerEntry;
import com.wallet.ledger.domain.LedgerTransaction;
import com.wallet.ledger.domain.OutboxEvent;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class DepositControllerTest {

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

    private UUID testAccountId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        
        // Re-initialize system wallet since we wiped the DB
        systemWalletInitializer.initSystemWallet();

        Account account = new Account("INR");
        account = accountRepository.save(account);
        testAccountId = account.getId();
    }

    @Test
    void testDepositSuccess() throws Exception {
        String request = "{\"amount\": 50000}";

        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.balance").value(50000))
                .andExpect(jsonPath("$.status").value("POSTED"));

        List<LedgerTransaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getType().name()).isEqualTo("DEPOSIT");
        assertThat(txs.get(0).getIdempotencyKey()).isNull();

        List<LedgerEntry> entries = entryRepository.findAll();
        assertThat(entries).hasSize(2);
        
        long sum = entries.stream().mapToLong(LedgerEntry::getAmount).sum();
        assertThat(sum).isEqualTo(0);

        LedgerEntry sysEntry = entries.stream().filter(e -> e.getAmount() < 0).findFirst().get();
        LedgerEntry custEntry = entries.stream().filter(e -> e.getAmount() > 0).findFirst().get();

        assertThat(sysEntry.getAmount()).isEqualTo(-50000);
        assertThat(sysEntry.getAccount().getId()).isEqualTo(SystemWalletInitializer.SYSTEM_INR_WALLET_ID);
        
        assertThat(custEntry.getAmount()).isEqualTo(50000);
        assertThat(custEntry.getAccount().getId()).isEqualTo(testAccountId);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("DEPOSIT_COMPLETED");
    }

    @Test
    void testDeposit_WithIdempotencyKey_Success() throws Exception {
        String key = "deposit-key-101";
        String request = "{\"amount\": 50000}";

        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.balance").value(50000))
                .andExpect(jsonPath("$.status").value("POSTED"));

        List<LedgerTransaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getType().name()).isEqualTo("DEPOSIT");
        assertThat(txs.get(0).getIdempotencyKey()).isEqualTo(key);

        List<LedgerEntry> entries = entryRepository.findAll();
        assertThat(entries).hasSize(2);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("DEPOSIT_COMPLETED");
    }

    @Test
    void testDeposit_DuplicateKey_SamePayload_ReturnsOriginal() throws Exception {
        String key = "deposit-key-same-102";
        String request = "{\"amount\": 50000}";

        // First request
        MvcResult firstResult = mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andReturn();

        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();
        long outboxCount = outboxEventRepository.count();

        // Second request - exact retry
        MvcResult retryResult = mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.balance").value(50000))
                .andReturn();

        assertThat(retryResult.getResponse().getContentAsString())
                .isEqualTo(firstResult.getResponse().getContentAsString());

        // Invariants: no extra transaction, entries, or outbox events
        assertThat(transactionRepository.count()).isEqualTo(txCount).isEqualTo(1);
        assertThat(entryRepository.count()).isEqualTo(entryCount).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount).isEqualTo(1);

        Long balance = entryRepository.getBalanceByAccountId(testAccountId);
        assertThat(balance).isEqualTo(50000);
    }

    @Test
    void testDeposit_DuplicateKey_DifferentAmount_Returns409() throws Exception {
        String key = "deposit-key-diff-amt-103";
        String request1 = "{\"amount\": 50000}";
        String request2 = "{\"amount\": 70000}";

        // First request
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request1))
                .andExpect(status().isCreated());

        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();
        long outboxCount = outboxEventRepository.count();

        // Second request with different amount -> 409 Conflict
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request2))
                .andExpect(status().isConflict());

        assertThat(transactionRepository.count()).isEqualTo(txCount);
        assertThat(entryRepository.count()).isEqualTo(entryCount);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);

        Long balance = entryRepository.getBalanceByAccountId(testAccountId);
        assertThat(balance).isEqualTo(50000);
    }

    @Test
    void testDeposit_DuplicateKey_DifferentAccount_Returns409() throws Exception {
        Account otherAccount = accountRepository.save(new Account("INR"));
        String key = "deposit-key-diff-acc-104";
        String request = "{\"amount\": 50000}";

        // First deposit to testAccountId
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated());

        long txCount = transactionRepository.count();
        long entryCount = entryRepository.count();
        long outboxCount = outboxEventRepository.count();

        // Attempt deposit with same key to otherAccount -> 409 Conflict
        mockMvc.perform(post("/api/accounts/" + otherAccount.getId() + "/deposits")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isConflict());

        assertThat(transactionRepository.count()).isEqualTo(txCount);
        assertThat(entryRepository.count()).isEqualTo(entryCount);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);

        Long otherBalance = entryRepository.getBalanceByAccountId(otherAccount.getId());
        assertThat(otherBalance).isEqualTo(0);
    }

    @Test
    void testDeposit_DuplicateKey_UsedForTransfer_Returns409() throws Exception {
        Account accountB = accountRepository.save(new Account("INR"));

        // First seed testAccountId with 100000
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 100000}"))
                .andExpect(status().isCreated());

        String sharedKey = "cross-type-key-105";

        // Execute a transfer using sharedKey
        String transferReq = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}",
                testAccountId, accountB.getId());
        mockMvc.perform(post("/api/transfers")
                .header("Idempotency-Key", sharedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferReq))
                .andExpect(status().isCreated());

        // Attempt deposit using the same sharedKey -> 409 Conflict (different operation type)
        String depositReq = "{\"amount\": 10000}";
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", sharedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(depositReq))
                .andExpect(status().isConflict());
    }

    @Test
    void testDeposit_InvalidIdempotencyKey_Blank_Returns400() throws Exception {
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeposit_InvalidIdempotencyKey_TooLong_Returns400() throws Exception {
        String longKey = "a".repeat(256);
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .header("Idempotency-Key", longKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeposit_OmittedIdempotencyKey_Compatibility() throws Exception {
        // Omitting Idempotency-Key header creates new deposits without constraint conflict
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(50000));

        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(70000));

        List<LedgerTransaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(2);
        assertThat(txs.get(0).getIdempotencyKey()).isNull();
        assertThat(txs.get(1).getIdempotencyKey()).isNull();

        List<LedgerEntry> entries = entryRepository.findAll();
        assertThat(entries).hasSize(4);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(2);
    }

    @Test
    void testDeposit_ConcurrentDuplicateRequests() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger createdCount = new AtomicInteger(0);

        String idempotencyKey = "concurrent-deposit-key-106";
        String request = "{\"amount\": 25000}";

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.amount").value(25000))
                            .andExpect(jsonPath("$.balance").value(25000));
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

        // Assert exactly one deposit transaction, two ledger entries, one outbox event
        List<LedgerTransaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getType().name()).isEqualTo("DEPOSIT");
        assertThat(txs.get(0).getIdempotencyKey()).isEqualTo(idempotencyKey);

        List<LedgerEntry> entries = entryRepository.findAll();
        assertThat(entries).hasSize(2);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("DEPOSIT_COMPLETED");

        Long balance = entryRepository.getBalanceByAccountId(testAccountId);
        assertThat(balance).isEqualTo(25000);

        executor.shutdown();
    }

    @Test
    void testMultipleDeposits() throws Exception {
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(70000));
                
        List<LedgerTransaction> txs = transactionRepository.findAll();
        assertThat(txs).hasSize(2);
        
        List<LedgerEntry> entries = entryRepository.findAll();
        assertThat(entries).hasSize(4);
    }

    @Test
    void testDepositZero() throws Exception {
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDepositNegative() throws Exception {
        mockMvc.perform(post("/api/accounts/" + testAccountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": -100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDepositNonexistentAccount() throws Exception {
        mockMvc.perform(post("/api/accounts/" + UUID.randomUUID() + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 500}"))
                .andExpect(status().isNotFound());
    }
}
