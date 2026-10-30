import http from 'k6/http';
import { check } from 'k6';

/**
 * CENARIO 3 (k6): Abordagem 3 - Versionamento Monotonico (Mensagens Fora de Ordem + Duplicadas)
 *
 * Envia eventos fora de ordem (v1 -> v3 -> v2 atrasado -> v3 duplicado) para:
 *   1. /api/wrong/customers/upsert    (Upsert cego sem checar version)
 *   2. /api/fixed/customers/versioned (UPDATE ... WHERE id = :id AND version < :version)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const headers = { 'Content-Type': 'application/json' };
  const wrongCustomerId = 701;
  const fixedCustomerId = 702;

  const events = [
    { version: 1, name: 'Leonardo v1 (Inicial)', email: 'v1@email.com' },
    { version: 3, name: 'Leonardo v3 (Mais Recente!)', email: 'v3@email.com' },
    { version: 2, name: 'Leonardo v2 (ATRASADO / FORA DE ORDEM)', email: 'v2@email.com' },
    { version: 3, name: 'Leonardo v3 (Duplicata)', email: 'v3@email.com' },
  ];

  for (const ev of events) {
    // 1. Envia para o Upsert SEM versionamento
    http.post(
      `${BASE_URL}/api/wrong/customers/upsert`,
      JSON.stringify({
        messageId: '33333333-0000-0000-0000-' + String(ev.version).padStart(12, '0'),
        customerId: wrongCustomerId,
        version: ev.version,
        name: ev.name,
        email: ev.email,
      }),
      { headers }
    );

    // 2. Envia para o Consumidor COM Versionamento Monotonico (Abordagem 3)
    http.post(
      `${BASE_URL}/api/fixed/customers/versioned`,
      JSON.stringify({
        messageId: '44444444-0000-0000-0000-' + String(ev.version).padStart(12, '0'),
        customerId: fixedCustomerId,
        version: ev.version,
        name: ev.name,
        email: ev.email,
      }),
      { headers }
    );
  }

  const wrongFinal = http.get(`${BASE_URL}/api/customers/${wrongCustomerId}`).json();
  const fixedFinal = http.get(`${BASE_URL}/api/customers/${fixedCustomerId}`).json();

  console.log('\n====================================================================');
  console.log('RESULTADO APOS SEQUENCIA FORA DE ORDEM: v1 -> v3 -> v2 (atrasado)');
  console.log('--------------------------------------------------------------------');
  console.log(`❌ Upsert Sem Versao : v${wrongFinal.version} - "${wrongFinal.name}"`);
  console.log(`✅ Com Versionamento : v${fixedFinal.version} - "${fixedFinal.name}"`);
  console.log('====================================================================\n');

  check(fixedFinal, {
    'Abordagem 3 manteve a versao 3': (c) => c.version === 3,
    'Abordagem 3 nao deixou o v2 atrasado sobrescrever': (c) => c.name === 'Leonardo v3 (Mais Recente!)',
  });
}

