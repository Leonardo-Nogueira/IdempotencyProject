package com.leonardonogueira.idempotency.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovementEntity, UUID> {

    /**
     * Lanca um movimento imutavel no Ledger de Estoque.
     * Como o proprio movementId e a PRIMARY KEY da tabela de negocio,
     * a operacao cumulativa se torna NATURALMENTE IDEMPOTENTE (sem precisar da tabela processed_message!).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO stock_movement (id, sku, delta, created_at)
        VALUES (:id, :sku, :delta, CURRENT_TIMESTAMP)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int tryInsertMovement(@Param("id") UUID id,
                          @Param("sku") String sku,
                          @Param("delta") int delta);

    /**
     * O saldo atual do estoque e simplesmente a soma dos movimentos imutaveis do SKU!
     */
    @Query("SELECT COALESCE(SUM(m.delta), 0) FROM StockMovementEntity m WHERE m.sku = :sku")
    int getCurrentStock(@Param("sku") String sku);

    @Transactional
    @Modifying
    @Query("DELETE FROM StockMovementEntity m WHERE m.sku = :sku")
    void deleteAllBySku(@Param("sku") String sku);
}

