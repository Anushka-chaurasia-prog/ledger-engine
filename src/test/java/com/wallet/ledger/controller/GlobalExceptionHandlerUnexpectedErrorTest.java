package com.wallet.ledger.controller;

import com.wallet.ledger.service.AccountQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that unexpected exceptions (non-ResponseStatusException, non-validation)
 * are caught by GlobalExceptionHandler and return a sanitized 500 ErrorResponse
 * with no internal exception details leaking to the client.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class GlobalExceptionHandlerUnexpectedErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountQueryService accountQueryService;

    @Test
    void testUnexpectedException_Returns500AndGenericMessage_WithNoInternalDetails() throws Exception {
        // Arrange: force a raw RuntimeException from the service layer — simulates
        // an unchecked DB/infrastructure failure that bypasses ResponseStatusException.
        when(accountQueryService.getBalance(any()))
                .thenThrow(new RuntimeException("SECRET_POSTGRES_INTERNAL_ERROR: column does not exist"));

        // Act: call a valid endpoint that delegates to the mocked service
        mockMvc.perform(get("/api/accounts/{id}/balance", UUID.randomUUID()))
                // Assert: HTTP 500
                .andExpect(status().isInternalServerError())
                // Assert: unified ErrorResponse structure
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected internal server error occurred"))
                // Assert: no internal details leak
                .andExpect(jsonPath("$.message").value(not(containsString("SECRET_POSTGRES"))))
                .andExpect(jsonPath("$.message").value(not(containsString("RuntimeException"))))
                .andExpect(content().string(not(containsString("SECRET_POSTGRES_INTERNAL_ERROR"))))
                .andExpect(content().string(not(containsString("RuntimeException"))))
                .andExpect(content().string(not(containsString("column does not exist"))));
    }
}
