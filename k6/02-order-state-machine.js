import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

/**
 * CENARIO 2 (k6): Abordagem 1 - Maquina de Estados (PENDING -> PAID)
 *
 * 30 VUs simultaneos tentam aprovar o mesmo pedido no exato mesmo instante.
 * O UPDATE condicional (WHERE id = :id AND status = 'PENDING') garante que
 * apenas 1 VU faz a transicao para PAID!
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDER_ID = 500;
const transitionsCounter = new Counter('transitions_to_paid');

export const options = {
  scenarios: {
    concurrent_order_approvals: {
      executor: 'per-vu-iterations',
      vus: 30,
      iterations: 1,
    },
  },
  thresholds: {
    transitions_to_paid: ['count == 1'],
  },
};

export function setup() {
  const headers = { 'Content-Type': 'application/json' };
  http.post(`${BASE_URL}/api/orders`, JSON.stringify({ id: ORDER_ID, amount: 250.0 }), { headers });
  return {
    messageId: '22222222-3333-4444-5555-' + Date.now().toString().slice(-12).padStart(12, '0'),
  };
}

export default function (data) {
  const headers = { 'Content-Type': 'application/json' };
  const res = http.post(
    `${BASE_URL}/api/fixed/orders/pay`,
    JSON.stringify({ messageId: data.messageId, orderId: ORDER_ID, version: 1 }),
    { headers }
  );

  check(res, { 'status is 200': (r) => r.status === 200 });
  if (res.status === 200 && res.json('transitionedToPaid') === true) {
    transitionsCounter.add(1);
  }
}

export function teardown() {
  const order = http.get(`${BASE_URL}/api/orders/${ORDER_ID}`).json();
  console.log('\n====================================================================');
  console.log(`✅ ABORDAGEM 1 (MAQUINA DE ESTADOS): Pedido #${order.id} -> Status Final: ${order.status}`);
  console.log('====================================================================\n');
}

