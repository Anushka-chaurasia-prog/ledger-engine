package com.wallet.ledger.controller;

import com.wallet.ledger.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private com.wallet.ledger.repository.LedgerTransactionRepository transactionRepository;

    @Autowired
    private com.wallet.ledger.repository.LedgerEntryRepository entryRepository;

    @Autowired
    private com.wallet.ledger.repository.OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void testCreateAccountSuccess() throws Exception {
        String request = "{\"currency\": \"INR\"}";

        mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(accountRepository.findAll()).hasSize(1);
    }

    @Test
    void testCreateAccountMissingCurrency() throws Exception {
        String request = "{}";

        mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.currency").exists());
    }

    @Test
    void testCreateAccountUnsupportedCurrency() throws Exception {
        String request = "{\"currency\": \"USD\"}";

        mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.currency").value("Unsupported currency. Only INR is supported in V1"));
    }
    
    @Test
    void testCreateAccountInvalidCurrencyLength() throws Exception {
        String request = "{\"currency\": \"IN\"}";

        mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.currency").exists());
    }
}
