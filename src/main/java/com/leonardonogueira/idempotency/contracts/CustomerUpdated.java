package com.leonardonogueira.idempotency.contracts;

import java.util.UUID;

/**
 * Evento de atualizacao de cliente com estado completo + versao monotonica.
 * Ideal para Abordagem 3 (Versionamento), que resolve duplicatas E mensagens fora de ordem.
 */
public record CustomerUpdated(
        UUID messageId,
        Long customerId,
        long version,
        String name,
        String email
) {}

