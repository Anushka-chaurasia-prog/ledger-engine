package com.wallet.ledger.controller;

import com.wallet.ledger.dto.TransferRequest;
import com.wallet.ledger.dto.TransferResponse;
import com.wallet.ledger.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Optional;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.length() > 255) {
            return ResponseEntity.badRequest().build();
        }

        // 1. Check if it already exists (fast path)
        Optional<TransferResponse> existing = transferService.getExistingTransfer(idempotencyKey, request);
        if (existing.isPresent()) {
            return new ResponseEntity<>(existing.get(), HttpStatus.CREATED);
        }

        try {
            // 2. Try to execute the transfer
            TransferResponse response = transferService.transfer(request, idempotencyKey);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 3. Concurrent race! Another thread successfully inserted it
            // Fallback to fetch the transaction that was just committed by the other thread
            TransferResponse existingResponse = transferService.getExistingTransfer(idempotencyKey, request)
                    .orElseThrow(() -> e); // If we still can't find it, rethrow
            return new ResponseEntity<>(existingResponse, HttpStatus.CREATED);
        }
    }
}
