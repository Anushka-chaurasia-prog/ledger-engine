package com.wallet.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.domain.Account;
import com.wallet.ledger.dto.DepositRequest;
import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.repository.AccountRepository;
import com.wallet.ledger.repository.LedgerEntryRepository;
import com.wallet.ledger.repository.LedgerTransactionRepository;
import com.wallet.ledger.repository.OutboxEventRepository;
import com.wallet.ledger.service.DepositService;
import com.wallet.ledger.service.SystemWalletInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "scheduler.enabled=false"
})
public class ApiErrorIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private SystemWalletInitializer systemWalletInitializer;

    @Autowired
    private DepositService depositService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        systemWalletInitializer.initSystemWallet();
    }

    // -----------------------------------------------------------------------
    // 1. DTO Validation → 400 + unified JSON
    // -----------------------------------------------------------------------
    @Test
    void testDtoValidation_Returns400AndUnifiedJson() {
        // amount = 0 → fails @Min(1) on DepositRequest
        DepositRequest req = new DepositRequest(0L);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/accounts/{accountId}/deposits", req, Map.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Bad Request");
        assertThat(body.get("message")).isEqualTo("Validation failed");

        Map<String, String> details = (Map<String, String>) body.get("details");
        assertThat(details).containsKey("amount");
        assertThat(details.get("amount")).isEqualTo("Amount must be greater than 0");
    }

    // -----------------------------------------------------------------------
    // 2. Account not found → 404 + unified JSON
    // -----------------------------------------------------------------------
    @Test
    void testAccountNotFound_Returns404AndUnifiedJson() {
        DepositRequest req = new DepositRequest(500L);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/accounts/{accountId}/deposits", req, Map.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not Found");
        assertThat(body.get("message")).isEqualTo("Account not found");
        assertThat(body).doesNotContainKey("details");
    }

    // -----------------------------------------------------------------------
    // 3. Insufficient funds → 400 + unified JSON
    // -----------------------------------------------------------------------
    @Test
    void testInsufficientFunds_Returns400AndUnifiedJson() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        // from has zero balance → 500 transfer will fail

        TransferRequest req = new TransferRequest(from.getId(), to.getId(), 500L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/transfers", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Bad Request");
        assertThat(body.get("message")).isEqualTo("Insufficient funds");
    }

    // -----------------------------------------------------------------------
    // 4. Idempotency conflict → 409 + unified JSON
    // -----------------------------------------------------------------------
    @Test
    void testIdempotencyConflict_Returns409AndUnifiedJson() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        depositService.deposit(from.getId(), new DepositRequest(1000L));

        String idempotencyKey = UUID.randomUUID().toString();

        // First transfer with 500 — succeeds
        TransferRequest req1 = new TransferRequest(from.getId(), to.getId(), 500L);
        HttpHeaders h1 = new HttpHeaders();
        h1.set("Idempotency-Key", idempotencyKey);
        restTemplate.exchange("/api/transfers", HttpMethod.POST, new HttpEntity<>(req1, h1), Map.class);

        // Second transfer with 600 and same idempotency key → 409 CONFLICT
        TransferRequest req2 = new TransferRequest(from.getId(), to.getId(), 600L);
        HttpHeaders h2 = new HttpHeaders();
        h2.set("Idempotency-Key", idempotencyKey);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/transfers", HttpMethod.POST, new HttpEntity<>(req2, h2), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(409);
        assertThat(body.get("error")).isEqualTo("Conflict");
        assertThat(body.get("message")).isEqualTo("Idempotency key already used with a different request");
    }

    // -----------------------------------------------------------------------
    // 5. Duplicate reversal → 409 + unified JSON
    // -----------------------------------------------------------------------
    @Test
    void testDuplicateReversal_Returns409AndUnifiedJson() {
        Account from = accountRepository.save(new Account("INR"));
        Account to = accountRepository.save(new Account("INR"));
        depositService.deposit(from.getId(), new DepositRequest(1000L));

        TransferRequest req = new TransferRequest(from.getId(), to.getId(), 500L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> transferResponse = restTemplate.exchange(
                "/api/transfers", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);

        String txId = transferResponse.getBody().get("transactionId").toString();

        // First reversal — succeeds
        restTemplate.postForEntity("/api/transfers/" + txId + "/reverse", null, Map.class);

        // Second reversal with same txId → 409
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/transfers/" + txId + "/reverse", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(409);
        assertThat(body.get("error")).isEqualTo("Conflict");
        assertThat(body.get("message")).isEqualTo("Transaction already reversed");
    }

    // -----------------------------------------------------------------------
    // 6. Malformed UUID path variable → safe 400, no java.util.UUID in body
    // -----------------------------------------------------------------------
    @Test
    void testMalformedUuid_Returns400AndNoJavaTypes() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/accounts/not-a-valid-uuid/balance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Bad Request");
        assertThat(body.get("message")).isEqualTo("Invalid parameter format");

        String bodyStr = body.toString();
        assertThat(bodyStr).doesNotContain("java.util.UUID");
    }
}
