package com.wallet.ledger.repository;

import com.wallet.ledger.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
    Optional<LedgerTransaction> findByReversesTx(LedgerTransaction reversesTx);
}
