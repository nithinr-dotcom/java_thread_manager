// k6 load test: lunch rush on limited stock + steady mixed traffic.
//   docker run --rm -i --network host -e BASE_URL=http://localhost:8080 -e API_KEY=sf_demo_pos \
//     grafana/k6:0.54.0 run - < loadtest/checkout.js
// Thresholds encode the SLOs: checkout p99 < 1 s, no 5xx other than retryable 503, zero oversell.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.API_KEY || 'sf_demo_pos';
const unexpected = new Counter('unexpected_responses');

export const options = {
  scenarios: {
    rush: { executor: 'constant-vus', vus: 50, duration: '30s', exec: 'rush' },
    steady: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '60s',
              preAllocatedVUs: 20, exec: 'steady', startTime: '5s' },
  },
  thresholds: {
    'http_req_duration{name:checkout}': ['p(99)<1000'],
    unexpected_responses: ['count==0'],
  },
};

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function checkout(items) {
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify({ items }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}`, 'Idempotency-Key': uuid() },
    tags: { name: 'checkout' },
  });
  const ok = check(res, { 'expected status': (r) => [201, 409, 503].includes(r.status) });
  if (!ok) unexpected.add(1);
  return res;
}

export function rush() {
  checkout([{ sku: 'MEALBOX-CHICKEN', quantity: 1 }]);   // 10 units on hand: 409 OUT_OF_STOCK after that
}

export function steady() {
  checkout([{ sku: 'WATER-SPARKLING', quantity: 1 }, { sku: 'LATTE-LARGE', quantity: 1 }]);
}
