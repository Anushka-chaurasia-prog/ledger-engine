package com.wallet.ledger.controller;

import com.wallet.ledger.dto.ReversalResponse;
import com.wallet.ledger.service.ReversalService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
public class ReversalController {

    private final ReversalService reversalService;

    public ReversalController(ReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping("/{transactionId}/reverse")
    public ResponseEntity<ReversalResponse> reverse(@PathVariable UUID transactionId) {
        try {
            ReversalResponse response = reversalService.reverse(transactionId);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (DataIntegrityViolationException e) {
            // Reversal transaction violated unique constraint on reverses_tx_id
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction already reversed");
        }
    }
}
