package com.wallet.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionStatus status;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_tx_id", unique = true)
    private LedgerTransaction reversesTx;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LedgerTransaction() {
    }

    public LedgerTransaction(TransactionType type, TransactionStatus status, String idempotencyKey) {
        this.type = type;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public LedgerTransaction getReversesTx() {
        return reversesTx;
    }

    public void setReversesTx(LedgerTransaction reversesTx) {
        this.reversesTx = reversesTx;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
