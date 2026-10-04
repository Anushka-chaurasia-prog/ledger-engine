# High-Throughput Wallet & Ledger System (`ledger-engine`)

A production-grade, double-entry financial ledger and wallet engine built with Java 21, Spring Boot 3, and PostgreSQL. Engineered for high concurrency, strict financial correctness, ACID compliance, zero lost updates, and low-latency throughput.

---

## Key Architectural Principles

1. **Immutable Double-Entry Bookkeeping**:
   - Accounts do not maintain a mutable `balance` column.
   - Every financial event creates an immutable `LedgerTransaction` and at least two balanced `LedgerEntry` records whose net sum is zero.
   - Balances are computed deterministically via `SUM(amount)` over ledger entries.

2. **Concurrency Safety & Deadlock Prevention**:
   - Pessimistic row locking (`SELECT ... FOR UPDATE`) serialized deterministically by lexicographical account UUID ordering (`min(A, B)` before `max(A, B)`).
   - Prevents deadlocks under bidirectional cross-transfers ($A \rightarrow B$ and $B \rightarrow A$).
   - Balance verification occurs strictly *after* row locks are acquired, preventing double-spend anomalies.

3. **Database-Enforced Invariant Hardening**:
   - PostgreSQL deferred constraint triggers (`trigger_check_double_entry_balance`) verify `SUM(amount) = 0` per transaction at commit time.
   - Non-zero amount check constraints (`amount <> 0`) prevent zero-valued entries.

4. **API-Level Idempotency & Safe Concurrent Recovery**:
   - Enforced by a unique constraint on `transactions.idempotency_key`.
   - Replaying identical requests returns cached responses with zero duplicate entries.
   - Concurrent duplicate requests are resolved cleanly outside rolled-back transactions.
   - Mismatched payloads with duplicate keys return `409 Conflict`.

5. **Transactional Outbox & Event Streaming**:
   - Emits domain events (`DEPOSIT_COMPLETED`, `TRANSFER_COMPLETED`, `TRANSFER_REVERSED`) atomically within the database transaction.
   - Polling scheduler publishes events to Apache Kafka with broker acknowledgements (`acks=all`) guaranteeing at-least-once delivery.

6. **Compensating Transactions (Reversals)**:
   - Supports 1:1 transfer reversals via compensating double entries and unique foreign key constraints (`reverses_tx_id`).

---

## Tech Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.3.3
- **Data & Persistence**: Spring Data JPA, Hibernate 6, PostgreSQL 17, HikariCP
- **Database Migrations**: Flyway 10
- **Messaging**: Spring Kafka (Transactional Outbox)
- **Benchmarking**: k6 v2.2.0, Node.js invariant verification suite
- **Testing**: JUnit 5, AssertJ, MockMvc, Spring Test (78 automated tests)

---

## Performance Benchmark Summary

Measured with **k6** across 15-second windows (HikariCP pool size = 10):

| Workload | Concurrency | Completed Requests | Throughput | p50 Latency | p95 Latency | Error Rate |
|---|---|---|---|---|---|---|
| **W1 (Independent Transfers)** | 10 VUs | 15,280 | **1,018.15 req/s** | 8.00 ms | 14.09 ms | 0.00% |
| **W2 (Hot-Sender Transfers)** | 5 VUs | 4,194 | **279.14 req/s** | 17.54 ms | 23.04 ms | 0.00% |
| **W3 (Balance Queries)** | 20 VUs | 52,505 | **3,499.05 req/s** | 3.63 ms | 12.36 ms | 0.00% |
| **W4 (History Queries — Optimized)** | 20 VUs | 29,965 | **1,997.66 req/s** | 5.61 ms | 19.74 ms | 0.00% |

*Query optimization (self-join + dedicated countQuery) yielded a **+28.2% throughput gain** and **-32.2% p95 latency reduction** on transaction history.*

---

## API Endpoints

### 1. Accounts
- `POST /api/accounts` — Create an account
  ```json
  { "currency": "INR" }
  ```
- `GET /api/accounts/{id}/balance` — Query calculated balance
- `GET /api/accounts/{id}/transactions?page=0&size=20` — Paginated transaction history

### 2. Deposits
- `POST /api/accounts/{id}/deposits`
  - Header: `Idempotency-Key` (Optional, string up to 255 chars)
  ```json
  { "amount": 50000 }
  ```

### 3. Transfers
- `POST /api/transfers`
  - Header: `Idempotency-Key` (Required, unique string)
  ```json
  {
    "fromAccountId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "toAccountId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
    "amount": 10000
  }
  ```

### 4. Reversals
- `POST /api/transfers/{transactionId}/reverse` — Reverse a posted transfer

---

## Getting Started

### Prerequisites
- JDK 21+
- Maven 3.9+
- PostgreSQL 16+
- Apache Kafka (optional for local outbox publishing)

### Run Database Migrations & Start Application
```bash
# Set your PostgreSQL credentials in application.yml or environment variables
mvn spring-boot:run
```

### Run Full Test Suite
```bash
mvn test
```
*(All 78 tests covering concurrency, money invariants, idempotency, rollbacks, and API error mappings pass with 0 failures).*

---

## License
MIT License
