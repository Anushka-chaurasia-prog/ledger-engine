package com.wallet.ledger.repository;

import com.wallet.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.account.id = :accountId")
    Long getBalanceByAccountId(@Param("accountId") UUID accountId);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    @Query(
        value = """
            SELECT new com.wallet.ledger.dto.TransactionHistoryDto(
                t.id, t.type, t.status, e.amount, 
                cp.account.id, 
                e.currency, e.createdAt
            )
            FROM LedgerEntry e
            JOIN e.transaction t
            LEFT JOIN LedgerEntry cp ON cp.transaction = t AND cp.account != e.account
            WHERE e.account.id = :accountId
            ORDER BY e.createdAt DESC, e.id DESC
        """,
        countQuery = """
            SELECT count(e)
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
        """
    )
    org.springframework.data.domain.Page<com.wallet.ledger.dto.TransactionHistoryDto> findTransactionHistoryByAccountId(
            @Param("accountId") UUID accountId,
            org.springframework.data.domain.Pageable pageable
    );
}
