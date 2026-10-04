import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom trends and rates
export const errorRate = new Rate('errors');
export const transferTrend = new Trend('transfer_latency', true);
export const balanceTrend = new Trend('balance_latency', true);
export const historyTrend = new Trend('history_latency', true);

// Configuration options
export const options = {
  scenarios: {
    // W1: Independent account-pair transfers
    w1_independent_transfers: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'runIndependentTransfers',
      startTime: '0s',
    },
    // W2: Concurrent transfers from one shared hot sender
    w2_hot_sender_transfers: {
      executor: 'constant-vus',
      vus: 5,
      duration: '20s',
      exec: 'runHotSenderTransfers',
      startTime: '35s',
    },
    // W3: Balance queries
    w3_balance_queries: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      exec: 'runBalanceQueries',
      startTime: '60s',
    },
    // W4: Paginated transaction-history queries
    w4_history_queries: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'runHistoryQueries',
      startTime: '95s',
    },
  },
  thresholds: {
    errors: ['rate<0.01'], // error rate should be less than 1%
    http_req_duration: ['p(95)<500'], // 95% of requests should be below 500ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Seeded account references (loaded from setup or environment)
let accounts = JSON.parse(open('./benchmark-accounts.json'));

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// W1: Independent Account Pairs
export function runIndependentTransfers() {
  const pairIndex = (__VU - 1) % accounts.independentPairs.length;
  const pair = accounts.independentPairs[pairIndex];
  
  const payload = JSON.stringify({
    fromAccountId: pair.sender,
    toAccountId: pair.receiver,
    amount: 1,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': generateUUID(),
    },
  };

  const res = http.post(`${BASE_URL}/api/transfers`, payload, params);
  const success = check(res, {
    'W1 status is 201': (r) => r.status === 201,
  });

  errorRate.add(!success);
  transferTrend.add(res.timings.duration);
}

// W2: Hot Sender Transfers
export function runHotSenderTransfers() {
  const receiverIndex = Math.floor(Math.random() * accounts.hotReceivers.length);
  const receiver = accounts.hotReceivers[receiverIndex];

  const payload = JSON.stringify({
    fromAccountId: accounts.hotSender,
    toAccountId: receiver,
    amount: 1,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': generateUUID(),
    },
  };

  const res = http.post(`${BASE_URL}/api/transfers`, payload, params);
  const success = check(res, {
    'W2 status is 201': (r) => r.status === 201,
  });

  errorRate.add(!success);
  transferTrend.add(res.timings.duration);
}

// W3: Balance Queries
export function runBalanceQueries() {
  const accountIndex = Math.floor(Math.random() * accounts.queryAccounts.length);
  const accountId = accounts.queryAccounts[accountIndex];

  const res = http.get(`${BASE_URL}/api/accounts/${accountId}/balance`);
  const success = check(res, {
    'W3 status is 200': (r) => r.status === 200,
  });

  errorRate.add(!success);
  balanceTrend.add(res.timings.duration);
}

// W4: Transaction History Queries
export function runHistoryQueries() {
  const accountIndex = Math.floor(Math.random() * accounts.queryAccounts.length);
  const accountId = accounts.queryAccounts[accountIndex];

  const res = http.get(`${BASE_URL}/api/accounts/${accountId}/transactions?page=0&size=20`);
  const success = check(res, {
    'W4 status is 200': (r) => r.status === 200,
  });

  errorRate.add(!success);
  historyTrend.add(res.timings.duration);
}
