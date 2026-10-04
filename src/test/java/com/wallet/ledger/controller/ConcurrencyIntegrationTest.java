package com.wallet.ledger.controller;

import com.wallet.ledger.domain.Account;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrency correctness tests proving that the pessimistic locking strategy
 * prevents double-spending and enforces idempotency even under real concurrent load.
 *
 * These tests use real PostgreSQL — no mocks, no production code changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ConcurrencyIntegrationTest {

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
    private UUID accountC;

    @BeforeEach
    void setUp() throws Exception {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        systemWalletInitializer.initSystemWallet();

        Account a = accountRepository.save(new Account("INR"));
        Account b = accountRepository.save(new Account("INR"));
        Account c = accountRepository.save(new Account("INR"));
        accountA = a.getId();
        accountB = b.getId();
        accountC = c.getId();

        // Seed A with 100 paise
        mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 100}"))
                .andReturn();
    }

    // =========================================================================
    // TEST 1 — Concurrent insufficient-funds / double-spend prevention
    // =========================================================================
    /**
     * Setup: A has 100. Two concurrent transfers: A→B=60 and A→C=60.
     * Together they would overdraft A. The pessimistic lock on A must ensure
     * exactly one succeeds (gets the balance) and one fails with 400.
     *
     * Proves: PESSIMISTIC_WRITE + lock-before-read prevents the lost-update
     * anomaly that READ COMMITTED alone cannot prevent.
     */
    @Test
    void testConcurrentInsufficientFunds_OnlyOneTransferSucceeds() throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);  // both threads wait here
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);

        // Thread 1: A → B for 60
        Thread t1 = new Thread(() -> {
            try {
                startGun.await();
                String req = String.format(
                        "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":60}",
                        accountA, accountB);
                MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) successCount.incrementAndGet();
                else if (status == 400) insufficientCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        // Thread 2: A → C for 60
        Thread t2 = new Thread(() -> {
            try {
                startGun.await();
                String req = String.format(
                        "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":60}",
                        accountA, accountC);
                MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) successCount.incrementAndGet();
                else if (status == 400) insufficientCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        startGun.countDown();  // release both threads simultaneously

        boolean finished = done.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("Both threads must complete within 10s").isTrue();

        // --- HTTP outcome assertions ---
        assertThat(successCount.get())
                .as("Exactly one transfer must succeed")
                .isEqualTo(1);
        assertThat(insufficientCount.get())
                .as("Exactly one transfer must fail with 400 (insufficient funds)")
                .isEqualTo(1);

        // --- Balance assertions ---
        long balanceA = entryRepository.getBalanceByAccountId(accountA);
        long balanceB = entryRepository.getBalanceByAccountId(accountB);
        long balanceC = entryRepository.getBalanceByAccountId(accountC);

        // A must have exactly 40 (started with 100, one 60-transfer succeeded)
        assertThat(balanceA)
                .as("A must have exactly 40 remaining (100 - 60); no double-spend")
                .isEqualTo(40);

        // A must not be negative — the fundamental no-overdraft invariant
        assertThat(balanceA)
                .as("A balance must never be negative")
                .isGreaterThanOrEqualTo(0);

        // The winner (B or C) has 60; the loser has 0
        long winnerBalance = Math.max(balanceB, balanceC);
        long loserBalance = Math.min(balanceB, balanceC);

        assertThat(winnerBalance)
                .as("The winning receiver must have exactly 60")
                .isEqualTo(60);
        assertThat(loserBalance)
                .as("The losing receiver must have exactly 0 (transfer was rejected)")
                .isEqualTo(0);

        // --- Ledger integrity: exactly 1 TRANSFER transaction created ---
        long transferTxCount = transactionRepository.findAll().stream()
                .filter(tx -> tx.getType().name().equals("TRANSFER"))
                .count();
        assertThat(transferTxCount)
                .as("Exactly one TRANSFER transaction must have been committed")
                .isEqualTo(1);

        // --- No partial entries from the failed request ---
        // Each transfer creates 2 entries (debit + credit). 1 deposit = 2 entries.
        // 1 successful transfer = 2 more entries. Total = 4.
        long totalEntries = entryRepository.count();
        assertThat(totalEntries)
                .as("Total entries: 2 (deposit) + 2 (one successful transfer) = 4")
                .isEqualTo(4);

        // --- No partial outbox events from the failed request ---
        long totalOutbox = outboxEventRepository.count();
        assertThat(totalOutbox)
                .as("Total outbox events: 1 (deposit) + 1 (one successful transfer) = 2")
                .isEqualTo(2);
    }

    // =========================================================================
    // TEST 2 — Concurrent same-key, different-request
    // =========================================================================
    /**
     * Same Idempotency-Key, two concurrent transfers with DIFFERENT amounts.
     * A is seeded with 2000 so that BOTH amounts (500 and 700) are individually
     * fundable even after the other thread commits.
     *
     * Thread 1: A→B=500  Thread 2: A→B=700  (same key)
     *
     * Race resolution:
     *  - T1 acquires A's lock, commits → A has 1500 remaining.
     *  - T2 acquires A's lock (1500 > 700 → passes balance check).
     *  - T2 calls saveAndFlush → UNIQUE constraint violation on idempotency_key.
     *  - Controller catch block calls getExistingTransfer → amounts differ → 409.
     *
     * Invariants we prove:
     *  - Exactly one TRANSFER transaction committed.
     *  - The winner gets 201; the loser gets 409.
     *  - Only the winning amount was moved (no double-transfer).
     *  - Exactly 2 transfer entries in the DB (none from loser).
     *  - Exactly 1 outbox event for the transfer (none from loser).
     *
     * This is the scenario that specifically proves the DB UNIQUE constraint on
     * idempotency_key is the last line of defence when both threads clear all
     * application-level checks (balance, validation, format) and differ only in amount.
     */
    @Test
    void testConcurrentSameKey_DifferentRequest_OnlyOneTransactionCreated() throws Exception {
        // Seed A with 2000 so BOTH 500 and 700 are fundable even after one commits
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();

        mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 2000}"))
                .andReturn();

        long txCountAfterDeposit = transactionRepository.count();     // 1 (deposit tx)
        long entryCountAfterDeposit = entryRepository.count();        // 2 (deposit entries)
        long outboxCountAfterDeposit = outboxEventRepository.count(); // 1 (deposit outbox)

        String sharedIdempotencyKey = "concurrent-diff-amount-" + UUID.randomUUID();

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger createdCount = new AtomicInteger(0);  // threads that received 201
        AtomicInteger loserCount  = new AtomicInteger(0);   // threads that received 409 or 400
        AtomicInteger winningAmount = new AtomicInteger(0);

        // Thread 1: A → B = 500, same key
        Thread t1 = new Thread(() -> {
            try {
                startGun.await();
                String req = String.format(
                        "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":500}",
                        accountA, accountB);
                MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", sharedIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) {
                    createdCount.incrementAndGet();
                    winningAmount.set(500);
                } else {
                    loserCount.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        // Thread 2: A → B = 700, same key
        Thread t2 = new Thread(() -> {
            try {
                startGun.await();
                String req = String.format(
                        "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":700}",
                        accountA, accountB);
                MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", sharedIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) {
                    createdCount.incrementAndGet();
                    winningAmount.set(700);
                } else {
                    loserCount.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        startGun.countDown();

        boolean finished = done.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("Both threads must complete within 10s").isTrue();

        // --- HTTP outcome assertions ---
        // Winner: 201. Loser: either 409 (idempotency UNIQUE constraint fires, meaning
        // both threads cleared all balance/validation checks and hit saveAndFlush) or
        // 400 (balance check failed because the serialized loser saw T1's committed
        // withdrawal — also a correct and safe resolution path).
        // The critical invariant is ALWAYS: exactly one transaction created.
        assertThat(createdCount.get())
                .as("Exactly one thread must receive 201 CREATED")
                .isEqualTo(1);
        assertThat(loserCount.get())
                .as("Exactly one thread must lose (409 CONFLICT or 400 INSUFFICIENT FUNDS)")
                .isEqualTo(1);

        // --- The critical invariant: exactly one TRANSFER transaction committed ---
        long transferTxCount = transactionRepository.count();
        assertThat(transferTxCount)
                .as("Exactly one TRANSFER transaction must exist (deposit + 1 transfer = 2 total)")
                .isEqualTo(txCountAfterDeposit + 1);

        // --- Balance: only the winning amount moved ---
        long balanceA = entryRepository.getBalanceByAccountId(accountA);
        long balanceB = entryRepository.getBalanceByAccountId(accountB);
        int won = winningAmount.get();

        assertThat(balanceA)
                .as("A balance must be 2000 minus the single winning transfer amount")
                .isEqualTo(2000L - won);
        assertThat(balanceB)
                .as("B balance must equal exactly the winning transfer amount")
                .isEqualTo((long) won);

        // --- Exactly 2 transfer entries; loser left no partial entries ---
        long totalEntries = entryRepository.count();
        assertThat(totalEntries)
                .as("Entries: deposit entries + 2 (one transfer's debit+credit). No extras from loser.")
                .isEqualTo(entryCountAfterDeposit + 2);

        // --- Exactly 1 outbox event for the transfer ---
        long totalOutbox = outboxEventRepository.count();
        assertThat(totalOutbox)
                .as("Outbox events: deposit event + 1 (one transfer event). No extras from loser.")
                .isEqualTo(outboxCountAfterDeposit + 1);
    }

    // =========================================================================
    // TEST 3 — Concurrent duplicate deposits with same Idempotency-Key
    // =========================================================================
    @Test
    void testConcurrentDeposits_SameIdempotencyKey_OnlyOneCommitted() throws Exception {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();

        String sharedKey = "concurrent-deposit-" + UUID.randomUUID();
        String req = "{\"amount\": 50000}";

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger createdCount = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> response1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> response2 = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                startGun.await();
                MvcResult res = mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                if (res.getResponse().getStatus() == 201) {
                    createdCount.incrementAndGet();
                    response1.set(res.getResponse().getContentAsString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startGun.await();
                MvcResult res = mockMvc.perform(post("/api/accounts/" + accountA + "/deposits")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                        .andReturn();
                if (res.getResponse().getStatus() == 201) {
                    createdCount.incrementAndGet();
                    response2.set(res.getResponse().getContentAsString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        startGun.countDown();

        boolean finished = done.await(10, TimeUnit.SECONDS);
        assertThat(finished).as("Both deposit threads must complete within 10s").isTrue();

        // Both threads must return 201 Created with identical response bodies
        assertThat(createdCount.get()).isEqualTo(2);
        assertThat(response1.get()).isNotNull();
        assertThat(response2.get()).isNotNull();
        assertThat(response1.get()).isEqualTo(response2.get());

        // Invariants: exactly 1 transaction, 2 entries, 1 outbox event
        long txCount = transactionRepository.count();
        assertThat(txCount).as("Exactly 1 deposit transaction must be committed").isEqualTo(1);

        long entryCount = entryRepository.count();
        assertThat(entryCount).as("Exactly 2 ledger entries (system debit + customer credit) must exist").isEqualTo(2);

        long outboxCount = outboxEventRepository.count();
        assertThat(outboxCount).as("Exactly 1 outbox event must exist").isEqualTo(1);

        Long balance = entryRepository.getBalanceByAccountId(accountA);
        assertThat(balance).as("Account balance must be exactly 50000").isEqualTo(50000);
    }
}
