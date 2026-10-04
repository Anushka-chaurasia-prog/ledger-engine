# High-Throughput Wallet & Ledger System — Performance Benchmark & Optimization Report

## 1. Executive Summary

This report documents the performance baseline, query optimizations, and connection pool scaling characteristics for the High-Throughput Wallet & Ledger System.

- **Stack**: Java 21, Spring Boot 3.3.3, Spring Data JPA / Hibernate, PostgreSQL 17, Flyway, Spring Kafka (Transactional Outbox).
- **Correctness Guarantees**: Complete ACID compliance, immutable double-entry ledger enforcement, pessimistic row locking with deterministic ordering, zero fractional loss, zero negative balance breaches.
- **Test Suite**: 68 automated unit, integration, concurrency, idempotency, and failure tests passing (`BUILD SUCCESS`).

---

## 2. Workload Specifications

- **W1 (Independent Transfers)**: Disjoint account pairs ($A_i \rightarrow B_i$) executed in parallel across distinct Virtual Users (VUs) with unique `Idempotency-Key` headers.
- **W2 (Hot-Sender Transfers)**: Concurrent transfers from a single shared sender account ($S \rightarrow R_i$) to evaluate lock serialization under contention.
- **W3 (Balance Queries)**: Read-only `GET /api/accounts/{id}/balance` calculating `SUM(amount)` over ledger entries.
- **W4 (Transaction History Queries)**: Paginated `GET /api/accounts/{id}/transactions?page=0&size=20` retrieving entry records, transaction metadata, and counterparty account IDs.

---

## 3. Initial Baseline Results (Phase 5.3)

Measured with **k6 v2.2.0** (15-second windows, HikariCP pool size = 10, SQL logging disabled):

| Workload ID | Description | VUs | Completed Requests | Throughput (RPS) | p50 Latency | p95 Latency | p99 Latency | Failure Rate |
|---|---|---|---|---|---|---|---|---|
| **W1.1** | Independent Transfers | 1 | 2,726 | 181.72 req/s | 4.86 ms | 7.22 ms | 18.52 ms | 0.00% |
| **W1.2** | Independent Transfers | 5 | 10,270 | 684.35 req/s | 6.46 ms | 10.85 ms | 24.18 ms | 0.00% |
| **W1.3** | Independent Transfers | 10 | 15,280 | 1,018.15 req/s | 8.00 ms | 14.09 ms | 31.84 ms | 0.00% |
| **W1.4** | Independent Transfers | 20 | 14,122 | 939.70 req/s | 15.85 ms | 37.48 ms | 78.42 ms | 0.00% |
| **W2.1** | Hot-Sender Transfers | 1 | 3,073 | 204.85 req/s | 4.30 ms | 6.32 ms | 14.71 ms | 0.00% |
| **W2.2** | Hot-Sender Transfers | 5 | 4,194 | 279.14 req/s | 17.54 ms | 23.04 ms | 32.11 ms | 0.00% |
| **W2.3** | Hot-Sender Transfers | 10 | 3,358 | 223.20 req/s | 41.64 ms | 66.92 ms | 98.45 ms | 0.00% |
| **W2.4** | Hot-Sender Transfers | 20 | 3,208 | 212.61 req/s | 91.68 ms | 117.64 ms | 144.10 ms | 0.00% |
| **W3.1** | Balance Queries | 5 | 43,605 | 2,906.82 req/s | 1.56 ms | 2.16 ms | 3.82 ms | 0.00% |
| **W3.2** | Balance Queries | 20 | 52,505 | 3,499.05 req/s | 3.63 ms | 12.36 ms | 28.91 ms | 0.00% |
| **W3.3** | Balance Queries | 50 | 52,026 | 3,466.42 req/s | 8.98 ms | 33.44 ms | 74.20 ms | 0.00% |
| **W4.1** | History Queries | 5 | 21,338 | 1,422.34 req/s | 3.33 ms | 4.81 ms | 8.65 ms | 0.00% |
| **W4.2** | History Queries | 20 | 23,380 | 1,558.02 req/s | 9.00 ms | 29.11 ms | 56.70 ms | 0.00% |
| **W4.3** | History Queries | 50 | 21,587 | 1,437.00 req/s | 24.32 ms | 84.18 ms | 162.50 ms | 0.00% |

---

## 4. Optimization 1: Transaction History Query (Self-Join + countQuery)

### Changes Applied:
Replaced the correlated scalar subquery in `LedgerEntryRepository.findTransactionHistoryByAccountId` with an explicit `LEFT JOIN LedgerEntry cp ON cp.transaction = t AND cp.account != e.account` and provided a dedicated `countQuery = "SELECT count(e) FROM LedgerEntry e WHERE e.account.id = :accountId"`.

### Measured Comparison:
| Concurrency (VUs) | Baseline Throughput | Optimized Throughput | Baseline p95 | Optimized p95 | Throughput Delta | Tail Latency Delta |
|---|---|---|---|---|---|---|
| **20 VUs** | 1,558.02 req/s | **1,997.66 req/s** | 29.11 ms | **19.74 ms** | **+28.2%** | **-32.2%** |
| **50 VUs** | 1,437.00 req/s | **1,953.03 req/s** | 84.18 ms | **59.55 ms** | **+35.9%** | **-29.3%** |

---

## 5. Optimization 2: HikariCP Connection Pool Evaluations

Repeated W1 (Independent Transfers) across pool sizes 10 and 15 (individual runs captured):

| Pool Size | Concurrency (VUs) | Run ID | Completed Requests | Throughput (RPS) | p50 Latency | p95 Latency | Error Rate |
|---|---|---|---|---|---|---|---|
| **10** | **10 VUs** | Run 1 | 12,614 | 840.51 req/s | 9.25 ms | 18.86 ms | 0.00% |
| **10** | **10 VUs** | Run 2 | 11,662 | 776.84 req/s | 10.60 ms | 18.24 ms | 0.00% |
| **10** | **20 VUs** | Run 1 | 11,874 | 789.78 req/s | 18.96 ms | 49.10 ms | 0.00% |
| **10** | **20 VUs** | Run 2 | 10,738 | 715.10 req/s | 21.34 ms | 49.92 ms | 0.00% |
| **15** | **10 VUs** | Run 1 | 10,348 | 689.19 req/s | 9.81 ms | 24.89 ms | 0.00% |
| **15** | **10 VUs** | Run 2 | 10,662 | 710.35 req/s | 10.63 ms | 22.56 ms | 0.00% |
| **15** | **20 VUs** | Run 1 | 7,993 | 530.96 req/s | 21.34 ms | 94.74 ms | 0.00% |
| **15** | **20 VUs** | Run 2 | 8,582 | 570.28 req/s | 19.50 ms | 63.47 ms | 0.00% |

---

## 6. Financial Invariant Verification

Post-benchmark invariant checks verified against PostgreSQL:
- **Conservation of Money**: All accounts across independent pairs and hot sender clusters maintained exact balance consistency ($100\%$).
- **Double-Entry Balance**: $0$ unbalanced transactions.
- **Negative Balances**: $0$ accounts with negative balances.
- **Outbox Integrity**: $1:1$ committed business transaction to outbox event correspondence.
