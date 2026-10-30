import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

/**
 * CENARIO 6 (k6 - Nivel Arquiteto):
 * Parte 1: Ledger Imutavel de Estoque (25 VUs simultaneos tentando dar baixa da mesma venda no estoque)
 * Parte 2: Recuperacao automatica de Idempotency-Key presa em IN_PROGRESS apos crash do servidor (Lease / locked_until)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SKU = 'IPHONE-16-PRO';
const ledgerAppliedCounter = new Counter('ledger_movements_applied');

export const options = {
  scenarios: {
    part1_immutable_ledger: {
      executor: 'per-vu-iterations',
      vus: 25,
      iterations: 1,
      exec: 'testImmutableLedger',
    },
    part2_lease_recovery: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '2s',
      exec: 'testStuckKeyLeaseRecovery',
    },
  },
  thresholds: {
    ledger_movements_applied: ['count == 1'],
  },
};

export function setup() {
  const headers = { 'Content-Type': 'application/json' };
  // Limpa o SKU e adiciona estoque inicial de +10 unidades
  http.del(`${BASE_URL}/api/stock/${SKU}`);
  const initialMovementId = '66666666-0000-0000-0000-' + Date.now().toString().slice(-12).padStart(12, '0');
  http.post(
    `${BASE_URL}/api/fixed/stock/movements`,
    JSON.stringify({ movementId: initialMovementId, sku: SKU, delta: 10 }),
    { headers }
  );

  return {
    saleMovementId: '77777777-1111-2222-3333-' + Date.now().toString().slice(-12).padStart(12, '0'),
  };
}

export function testImmutableLedger(data) {
  const headers = { 'Content-Type': 'application/json' };
  // 25 VUs simultaneos tentam registrar a MESMA baixa de -2 unidades no Ledger Imutavel
  const res = http.post(
    `${BASE_URL}/api/fixed/stock/movements`,
    JSON.stringify({ movementId: data.saleMovementId, sku: SKU, delta: -2 }),
    { headers }
  );

  check(res, { 'Ledger status 200': (r) => r.status === 200 });
  if (res.status === 200 && res.json('applied') === true) {
    ledgerAppliedCounter.add(1);
  }
}

export function testStuckKeyLeaseRecovery() {
  const stuckKey = 'stuck-key-' + Date.now();
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': stuckKey,
  };
  const payload = JSON.stringify({ orderId: 999, amount: 500.0 });

  // 1. Simula que o Servidor A reservou a chave com lease de 1 segundo (1000ms) e MORREU (kill -9)
  http.post(`${BASE_URL}/api/crash-simulation/stuck-key?leaseMillis=1000`, payload, { headers });

  // 2. Retry imediato (enquanto o lease de 1s ainda esta ativo) -> Deve retornar 409 Conflict!
  const retryWhileLocked = http.post(`${BASE_URL}/api/fixed/lease-recovery-payment`, payload, { headers });
  check(retryWhileLocked, {
    'Enquanto lease ativo -> retorna 409 Conflict': (r) => r.status === 409,
  });

  // 3. Aguarda 1.2s para o lease (locked_until) expirar
  sleep(1.2);

  // 4. Novo retry apos o lease expirar -> Servidor assume a chave orfa e conclui com 201 Created!
  const retryAfterExpired = http.post(`${BASE_URL}/api/fixed/lease-recovery-payment`, payload, { headers });
  check(retryAfterExpired, {
    'Apos lease expirar -> assume chave orfa e retorna 201 Created': (r) =>
      r.status === 201 && r.json('recoveredFromOrphanedKey') === true,
  });

  console.log('\n================================================================================');
  console.log('🔓 PARTE 2 (LEASE RECOVERY): Chave Presa em IN_PROGRESS apos Crash do Servidor');
  console.log(`1. Retry com Lease Ativo (t=0s)    -> Status ${retryWhileLocked.status} (Bloqueado: ainda em andamento)`);
  console.log(`2. Retry com Lease Expirado (t=1.2s)-> Status ${retryAfterExpired.status} | paymentId: ${retryAfterExpired.json('paymentId')} (Recuperada!)`);
  console.log('================================================================================');
}

export function teardown() {
  const stock = http.get(`${BASE_URL}/api/stock/${SKU}`).json();
  console.log('\n================================================================================');
  console.log(`📦 PARTE 1 (LEDGER IMUTAVEL): Estoque Inicial = 10 | 25 VUs baixando -2 -> Estoque Final = ${stock.currentStock} (Esperado: 8)`);
  console.log('================================================================================\n');
}

