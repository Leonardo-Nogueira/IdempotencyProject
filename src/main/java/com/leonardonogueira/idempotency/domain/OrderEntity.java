package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private Long id;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OrderEntity() {}

    public OrderEntity(Long id, BigDecimal amount, String status) {
        this.id = id;
        this.amount = amount;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setStatus(String status) { this.status = status; }
}

