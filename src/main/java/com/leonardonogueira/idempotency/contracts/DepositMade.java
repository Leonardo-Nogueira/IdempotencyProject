package com.leonardonogueira.idempotency.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de deposito em conta (Operacao cumulativa: saldo + delta).
 * Exige Abordagem 2 (Tabela Inbox / processed_message) porque + valor nao e naturalmente idempotente.
 */
public record DepositMade(
        UUID messageId,
        Long accountId,
        BigDecimal amount
) {}

