package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * CENARIO 6 (Nivel Arquiteto - Secao 2.7, linha 568):
 * "Como modelar estoque/saldo de forma idempotente por natureza?
 *  Movimentos imutaveis (ledger) com ID unico; o saldo e a soma."
 */
@Entity
@Table(name = "stock_movement")
public class StockMovementEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(nullable = false)
    private int delta;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected StockMovementEntity() {}

    public StockMovementEntity(UUID id, String sku, int delta) {
        this.id = id;
        this.sku = sku;
        this.delta = delta;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public int getDelta() { return delta; }
    public Instant getCreatedAt() { return createdAt; }
}

