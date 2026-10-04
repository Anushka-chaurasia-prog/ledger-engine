// Smoke Test & Micro-Harness for W1-W4
// Runs a small, deterministic smoke test measuring latency percentiles (p50, p95, p99) and status codes

import fs from 'fs';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

function calculatePercentiles(latencies) {
  if (latencies.length === 0) return { p50: 0, p95: 0, p99: 0, min: 0, max: 0, avg: 0 };
  const sorted = [...latencies].sort((a, b) => a - b);
  const p50 = sorted[Math.floor(sorted.length * 0.50)];
  const p95 = sorted[Math.floor(sorted.length * 0.95)];
  const p99 = sorted[Math.floor(sorted.length * 0.99)];
  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const avg = sorted.reduce((sum, val) => sum + val, 0) / sorted.length;
  return { p50, p95, p99, min, max, avg };
}

async function runConcurrentBatch(taskFn, totalRequests, concurrency) {
  const latencies = [];
  const statusCounts = {};
  let completed = 0;
  let cursor = 0;

  async function worker() {
    while (cursor < totalRequests) {
      const idx = cursor++;
      const start = performance.now();
      try {
        const status = await taskFn(idx);
        const duration = performance.now() - start;
        latencies.push(duration);
        statusCounts[status] = (statusCounts[status] || 0) + 1;
      } catch (err) {
        const duration = performance.now() - start;
        latencies.push(duration);
        statusCounts['ERROR'] = (statusCounts['ERROR'] || 0) + 1;
      }
      completed++;
    }
  }

  const workers = Array.from({ length: concurrency }, () => worker());
  await Promise.all(workers);

  return {
    total: completed,
    latencies,
    percentiles: calculatePercentiles(latencies),
    statusCounts
  };
}

async function main() {
  if (!fs.existsSync('benchmark/benchmark-accounts.json')) {
    console.error('Error: benchmark/benchmark-accounts.json not found. Run "node benchmark/seed-data.mjs" first.');
    process.exit(1);
  }

  const accounts = JSON.parse(fs.readFileSync('benchmark/benchmark-accounts.json', 'utf8'));
  console.log('====================================================');
  console.log('LEDGER SYSTEM PERFORMANCE SMOKE TEST (PHASE 5.2)');
  console.log('Target:', BASE_URL);
  console.log('====================================================\n');

  // --- W1: Independent Transfers ---
  console.log('Running W1: Independent Account Transfers (50 requests, concurrency 5)...');
  const w1Start = performance.now();
  const w1Result = await runConcurrentBatch(async (idx) => {
    const pair = accounts.independentPairs[idx % accounts.independentPairs.length];
    const res = await fetch(`${BASE_URL}/api/transfers`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID()
      },
      body: JSON.stringify({
        fromAccountId: pair.sender,
        toAccountId: pair.receiver,
        amount: 1
      })
    });
    return res.status;
  }, 50, 5);
  const w1TotalSec = (performance.now() - w1Start) / 1000;
  const w1Rps = (w1Result.total / w1TotalSec).toFixed(2);

  // --- W2: Hot Sender Transfers ---
  console.log('Running W2: Hot Sender Transfers (30 requests, concurrency 5)...');
  const w2Start = performance.now();
  const w2Result = await runConcurrentBatch(async (idx) => {
    const receiver = accounts.hotReceivers[idx % accounts.hotReceivers.length];
    const res = await fetch(`${BASE_URL}/api/transfers`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID()
      },
      body: JSON.stringify({
        fromAccountId: accounts.hotSender,
        toAccountId: receiver,
        amount: 1
      })
    });
    return res.status;
  }, 30, 5);
  const w2TotalSec = (performance.now() - w2Start) / 1000;
  const w2Rps = (w2Result.total / w2TotalSec).toFixed(2);

  // --- W3: Balance Queries ---
  console.log('Running W3: Balance Queries (100 requests, concurrency 10)...');
  const w3Start = performance.now();
  const w3Result = await runConcurrentBatch(async (idx) => {
    const acc = accounts.queryAccounts[idx % accounts.queryAccounts.length];
    const res = await fetch(`${BASE_URL}/api/accounts/${acc}/balance`);
    return res.status;
  }, 100, 10);
  const w3TotalSec = (performance.now() - w3Start) / 1000;
  const w3Rps = (w3Result.total / w3TotalSec).toFixed(2);

  // --- W4: Transaction History Queries ---
  console.log('Running W4: Transaction History Queries (50 requests, concurrency 5)...');
  const w4Start = performance.now();
  const w4Result = await runConcurrentBatch(async (idx) => {
    const acc = accounts.queryAccounts[idx % accounts.queryAccounts.length];
    const res = await fetch(`${BASE_URL}/api/accounts/${acc}/transactions?page=0&size=20`);
    return res.status;
  }, 50, 5);
  const w4TotalSec = (performance.now() - w4Start) / 1000;
  const w4Rps = (w4Result.total / w4TotalSec).toFixed(2);

  console.log('\n================ SMOKE TEST RESULTS ================');
  function printMetrics(name, result, rps) {
    console.log(`\n[${name}]`);
    console.log(`  Requests: ${result.total} | Throughput: ${rps} req/sec`);
    console.log(`  Status codes: ${JSON.stringify(result.statusCounts)}`);
    console.log(`  Latencies: avg=${result.percentiles.avg.toFixed(2)}ms, p50=${result.percentiles.p50.toFixed(2)}ms, p95=${result.percentiles.p95.toFixed(2)}ms, p99=${result.percentiles.p99.toFixed(2)}ms, min=${result.percentiles.min.toFixed(2)}ms, max=${result.percentiles.max.toFixed(2)}ms`);
  }

  printMetrics('W1: Independent Transfers', w1Result, w1Rps);
  printMetrics('W2: Hot Sender Transfers', w2Result, w2Rps);
  printMetrics('W3: Balance Queries', w3Result, w3Rps);
  printMetrics('W4: History Queries', w4Result, w4Rps);
  console.log('\n====================================================\n');
}

main().catch(err => {
  console.error('Smoke test failure:', err);
  process.exit(1);
});
