import http from 'k6/http';
import { check } from 'k6';

/**
 * CENARIO 4 (k6): API HTTP com Idempotency-Key (Padrao Stripe / Mercado Pago - Secao 2.4)
 *
 * Testa:
 * 1. Primeira chamada -> 201 Created (executa e grava transferId)
 * 2. Retry com mesma chave e mesmo payload -> 201 Created (devolve o EXATO mesmo transferId sem cobrar 2x)
 * 3. Reuso indevido da mesma chave com valor diferente -> 422 Unprocessable Entity
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const idemKey = 'idem-key-' + Date.now();
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': idemKey,
  };

  const payloadOriginal = JSON.stringify({ fromAccount: 10, toAccount: 20, amount: 150.0 });

  // 1. Primeira requisicao
  const res1 = http.post(`${BASE_URL}/api/fixed/transfers`, payloadOriginal, { headers });
  check(res1, { '1a chamada retornou 201 Created': (r) => r.status === 201 });

  // 2. Segunda requisicao (Retry do cliente com a mesma Idempotency-Key e mesmo payload)
  const res2 = http.post(`${BASE_URL}/api/fixed/transfers`, payloadOriginal, { headers });
  check(res2, {
    'Retry retornou 201 Created': (r) => r.status === 201,
    'Retry devolveu exatamente o mesmo transferId': (r) => r.json('transferId') === res1.json('transferId'),
  });

  // 3. Terceira requisicao: tenta reutilizar a mesma Idempotency-Key mudando o valor para 9999.00
  const payloadAdulterado = JSON.stringify({ fromAccount: 10, toAccount: 20, amount: 9999.0 });
  const res3 = http.post(`${BASE_URL}/api/fixed/transfers`, payloadAdulterado, { headers });
  check(res3, {
    'Payload diferente com mesma chave bloqueado com 422': (r) => r.status === 422,
  });

  console.log('\n====================================================================');
  console.log(`🔑 TESTE HTTP IDEMPOTENCY-KEY (${idemKey})`);
  console.log(`1a Chamada -> Status ${res1.status} | transferId: ${res1.json('transferId')}`);
  console.log(`2a Chamada (Retry) -> Status ${res2.status} | transferId: ${res2.json('transferId')} (Identico!)`);
  console.log(`3a Chamada (Payload Divergente) -> Status ${res3.status} | Body: ${res3.body}`);
  console.log('====================================================================\n');
}

