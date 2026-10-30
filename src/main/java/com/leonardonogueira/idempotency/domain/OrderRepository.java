package com.leonardonogueira.idempotency.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /**
     * Abordagem 1 (Naturalmente Idempotente + Maquina de Estados - Secao 2.2 / 2.3):
     * Define estado em vez de incrementar e so aceita a transicao se o estado atual permitir (PENDING -> PAID).
     * Retorna 1 na primeira execucao e 0 nas duplicatas ou se o pedido ja foi cancelado/estornado.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.status = 'PAID' WHERE o.id = :id AND o.status = 'PENDING'")
    int markAsPaidIfPending(@Param("id") Long id);
}
