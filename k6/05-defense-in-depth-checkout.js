import http from 'k6/http';
import { check } from 'k6';

/**
 * CENARIO 5 (k6): Defesa em Profundidade (Camada 1: Idempotency-Key + Camada 2: Maquina de Estados no Dominio)
 *
 * Simula a jornada real completa de um Checkout de Pagamento:
 *   Passo 1: Cliente tenta pagar o Pedido #900 com Idempotency-Key "key-tentativa-1" -> 201 Created (Pago!)
 *   Passo 2: Internet pisca e o app faz Retry Automatico com a MESMA "key-tentativa-1" -> 201 Created (Camada 1 devolve o mesmo paymentId!)
 *   Passo 3: Usuario da F5 no navegador (ou abre outra aba) e clica em "Pagar" de novo para o Pedido #900.
 *            O front-end gera uma NOVA Idempotency-Key ("key-tentativa-2-apos-f5")!
 *            - No endpoint /wrong/checkout-only-http-key -> Cobra o cliente pela 2a vez (201 Created com novo paymentId ❌)
 *            - No endpoint /fixed/checkout               -> A Camada 2 (WHERE status = 'PENDING') barra na hora com 409 Conflict (ORDER_ALREADY_PAID ✅)!
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const jsonHeaders = { 'Content-Type': 'application/json' };

  // Cria 2 pedidos PENDING (#901 para testar o incompleto e #902 para testar o completo)
  http.post(`${BASE_URL}/api/orders`, JSON.stringify({ id: 901, amount: 300.0 }), { headers: jsonHeaders });
  http.post(`${BASE_URL}/api/orders`, JSON.stringify({ id: 902, amount: 300.0 }), { headers: jsonHeaders });

  // =========================================================================
  // PARTE A: Testando o endpoint INCOMPLETO (So tem Camada 1 - Idempotency-Key)
  // =========================================================================
  const keyA1 = 'key-901-original-' + Date.now();
  const keyA2AfterF5 = 'key-901-apos-f5-' + Date.now();
  const body901 = JSON.stringify({ orderId: 901, amount: 300.0, paymentMethod: 'PIX' });

  const wrong1 = http.post(`${BASE_URL}/api/wrong/checkout-only-http-key`, body901, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keyA1 },
  });
  const wrong2AfterF5 = http.post(`${BASE_URL}/api/wrong/checkout-only-http-key`, body901, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keyA2AfterF5 },
  });

  // =========================================================================
  // PARTE B: Testando o PADRAO OURO (Camada 1: Idempotency-Key + Camada 2: State Machine)
  // =========================================================================
  const keyB1 = 'key-902-original-' + Date.now();
  const keyB2AfterF5 = 'key-902-apos-f5-' + Date.now();
  const body902 = JSON.stringify({ orderId: 902, amount: 300.0, paymentMethod: 'PIX' });

  // 1. Primeira chamada de pagamento do Pedido #902
  const fixed1 = http.post(`${BASE_URL}/api/fixed/checkout`, body902, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keyB1 },
  });

  // 2. Retry de rede com a MESMA Idempotency-Key (keyB1) -> Camada 1 devolve o comprovante salvo!
  const fixed2NetworkRetry = http.post(`${BASE_URL}/api/fixed/checkout`, body902, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keyB1 },
  });

  // 3. Cliente deu F5 e enviou uma NOVA Idempotency-Key (keyB2AfterF5) para o mesmo Pedido #902!
  // A Camada 1 deixa passar porque a chave e nova, mas a Camada 2 (WHERE status = 'PENDING') bloqueia!
  const fixed3AfterF5 = http.post(`${BASE_URL}/api/fixed/checkout`, body902, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': keyB2AfterF5 },
  });

  console.log('\n================================================================================');
  console.log('🛡️ CENARIO 5: DEFESA EM PROFUNDIDADE (Idempotency-Key + State Machine no Dominio)');
  console.log('--------------------------------------------------------------------------------');
  console.log('❌ [SO CAMADA 1] 1o Pagamento (key-1)        -> Status ' + wrong1.status + ' | paymentId: ' + wrong1.json('paymentId'));
  console.log('❌ [SO CAMADA 1] Cliente deu F5 (key-2 nova) -> Status ' + wrong2AfterF5.status + ' | paymentId: ' + wrong2AfterF5.json('paymentId') + ' (COBROU 2 VEZES!)');
  console.log('--------------------------------------------------------------------------------');
  console.log('✅ [CAMADA 1+2]  1o Pagamento (key-1)        -> Status ' + fixed1.status + ' | paymentId: ' + fixed1.json('paymentId'));
  console.log('✅ [CAMADA 1+2]  Retry de Rede (mesma key-1) -> Status ' + fixed2NetworkRetry.status + ' | paymentId: ' + fixed2NetworkRetry.json('paymentId') + ' (Comprovante identico pela Camada 1!)');
  console.log('✅ [CAMADA 1+2]  Cliente deu F5 (key-2 nova) -> Status ' + fixed3AfterF5.status + ' | ' + fixed3AfterF5.json('message'));
  console.log('================================================================================\n');

  check(fixed1, {
    '1o pagamento retornou 201 Created': (r) => r.status === 201,
  });
  check(fixed2NetworkRetry, {
    'Retry de rede (Camada 1) retornou 201 com o mesmo paymentId': (r) =>
      r.status === 201 && r.json('paymentId') === fixed1.json('paymentId'),
  });
  check(fixed3AfterF5, {
    'Duplo pagamento com nova chave apos F5 (Camada 2) foi bloqueado com 409': (r) =>
      r.status === 409 && r.json('error') === 'ORDER_ALREADY_PAID',
  });
}

