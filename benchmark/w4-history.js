import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

export const errorRate = new Rate('errors');
export const latencyTrend = new Trend('history_latency', true);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const accounts = JSON.parse(open('./benchmark-accounts.json'));

export default function () {
  const accountIndex = Math.floor(Math.random() * accounts.queryAccounts.length);
  const accountId = accounts.queryAccounts[accountIndex];

  const res = http.get(`${BASE_URL}/api/accounts/${accountId}/transactions?page=0&size=20`);
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  errorRate.add(!success);
  latencyTrend.add(res.timings.duration);
}
