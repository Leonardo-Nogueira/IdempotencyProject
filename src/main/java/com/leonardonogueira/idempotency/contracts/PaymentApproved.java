package com.leonardonogueira.idempotency.contracts;

import java.util.UUID;

/**
 * Evento de aprovacao de pagamento (Secao 2.2).
 * Importante: O messageId nasce no PRODUTOR antes do primeiro envio!
 */
public record PaymentApproved(
        UUID messageId,
        Long orderId,
        long version
) {}

