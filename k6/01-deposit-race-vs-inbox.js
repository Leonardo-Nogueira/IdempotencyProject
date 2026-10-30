import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

/**
 * CENARIO 1 (k6): Tempestade de Duplicatas Concorrentes em Operacao Cumulativa (Saldo + R$ 100)
 *
 * Dispara 20 Virtual Users (VUs) simultaneos enviando o EXATO MESMO evento DepositMade (mesmo messageId)
 * contra os 3 endpoints:
 *   1. /api/wrong/deposits/naive          (Sem idempotencia)
 *   2. /api/wrong/deposits/race-condition (Check-Then-Act: if !exists -> add -> save)
 *   3. /api/fixed/deposits/inbox          (Abordagem 2: INSERT ... ON CONFLICT DO NOTHING + @Transactional)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = 20;

const inboxAppliedCounter = new Counter('inbox_applied_times');
const raceConditionPassedCounter = new Counter('race_condition_passed_times');

export const options = {
  scenarios: {
    concurrent_redeliveries: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1, // Todos os 20 VUs disparam juntos!
      maxDuration: '30s',
    },
  },
  thresholds: {
    // Garante que na Abordagem 2 (Inbox), exatamente 1 requisicao aplicou o deposito!
    inbox_applied_times: ['count == 1'],
  },
};

export function setup() {
  const headers = { 'Content-Type': 'application/json' };

  // Cria/reseta as 3 contas com saldo R$ 0.00
  http.post(`${BASE_URL}/api/accounts`, JSON.stringify({ id: 1, owner: '1. Sem Idempotencia (Naive)' }), { headers });
  http.post(`${BASE_URL}/api/accounts`, JSON.stringify({ id: 2, owner: '2. Check-Then-Act (Race Condition)' }), { headers });
  http.post(`${BASE_URL}/api/accounts`, JSON.stringify({ id: 3, owner: '3. Transactional Inbox (Corrigido)' }), { headers });

  // Mesmo messageId (simulando reentregas / retries concorrentes da mesma mensagem)
  return {
    sharedMessageId: '11111111-2222-3333-4444-' + Date.now().toString().slice(-12).padStart(12, '0'),
    amount: 100.0,
  };
}

export default function (data) {
  const headers = { 'Content-Type': 'application/json' };

  // 1. Dispara contra o endpoint SEM idempotencia (Conta 1)
  http.post(
    `${BASE_URL}/api/wrong/deposits/naive`,
    JSON.stringify({ messageId: data.sharedMessageId, accountId: 1, amount: data.amount }),
    { headers }
  );

  // 2. Dispara contra o endpoint com Check-Then-Act (Conta 2 - sofre Race Condition!)
  const raceRes = http.post(
    `${BASE_URL}/api/wrong/deposits/race-condition`,
    JSON.stringify({ messageId: data.sharedMessageId, accountId: 2, amount: data.amount }),
    { headers }
  );
  if (raceRes.status === 200 && raceRes.json('applied') === true) {
    raceConditionPassedCounter.add(1);
  }

  // 3. Dispara contra o endpoint CORRIGIDO com Transactional Inbox (Conta 3)
  const inboxRes = http.post(
    `${BASE_URL}/api/fixed/deposits/inbox`,
    JSON.stringify({ messageId: data.sharedMessageId, accountId: 3, amount: data.amount }),
    { headers }
  );
  check(inboxRes, { 'status 200 no inbox': (r) => r.status === 200 });
  if (inboxRes.status === 200 && inboxRes.json('applied') === true) {
    inboxAppliedCounter.add(1);
  }
}

export function teardown(data) {
  const acc1 = http.get(`${BASE_URL}/api/accounts/1`).json();
  const acc2 = http.get(`${BASE_URL}/api/accounts/2`).json();
  const acc3 = http.get(`${BASE_URL}/api/accounts/3`).json();

  console.log('\n====================================================================');
  console.log(`RESULTADO REAL APOS ${VUS} REQUISICOES CONCORRENTES (MessageId: ${data.sharedMessageId})`);
  console.log(`Valor esperado na conta: R$ ${data.amount.toFixed(2)}`);
  console.log('--------------------------------------------------------------------');
  console.log(`❌ Conta 1 (Sem Idempotencia)         -> Saldo Final: R$ ${Number(acc1.balance).toFixed(2)}`);
  console.log(`❌ Conta 2 (Check-Then-Act / Race)    -> Saldo Final: R$ ${Number(acc2.balance).toFixed(2)}`);
  console.log(`✅ Conta 3 (Abordagem 2 - Inbox)      -> Saldo Final: R$ ${Number(acc3.balance).toFixed(2)}`);
  console.log('====================================================================\n');
}

