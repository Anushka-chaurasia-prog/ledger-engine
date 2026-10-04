import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

export const errorRate = new Rate('errors');
export const latencyTrend = new Trend('transfer_latency', true);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const accounts = JSON.parse(open('./benchmark-accounts.json'));

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

export default function () {
  // Use VU id to select disjoint account pair
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
    'status is 201': (r) => r.status === 201,
  });

  errorRate.add(!success);
  latencyTrend.add(res.timings.duration);
}
