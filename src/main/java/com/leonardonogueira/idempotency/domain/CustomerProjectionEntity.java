package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "customer_projection")
public class CustomerProjectionEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CustomerProjectionEntity() {}

    public CustomerProjectionEntity(Long id, String name, String email, long version) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.version = version;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}

