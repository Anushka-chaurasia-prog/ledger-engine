package com.wallet.ledger.controller;

import com.wallet.ledger.dto.DepositRequest;
import com.wallet.ledger.dto.DepositResponse;
import com.wallet.ledger.service.DepositService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<DepositResponse> deposit(
            @PathVariable UUID accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        if (idempotencyKey != null) {
            if (idempotencyKey.trim().isEmpty() || idempotencyKey.length() > 255) {
                return ResponseEntity.badRequest().build();
            }

            // Fast-path: Check if deposit already exists
            Optional<DepositResponse> existing = depositService.getExistingDeposit(idempotencyKey, accountId, request);
            if (existing.isPresent()) {
                return new ResponseEntity<>(existing.get(), HttpStatus.CREATED);
            }
        }

        try {
            DepositResponse response = depositService.deposit(accountId, request, idempotencyKey);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null) {
                // Concurrent race recovery outside the rolled-back transaction
                DepositResponse existingResponse = depositService.getExistingDeposit(idempotencyKey, accountId, request)
                        .orElseThrow(() -> e);
                return new ResponseEntity<>(existingResponse, HttpStatus.CREATED);
            }
            throw e;
        }
    }
}
