package com.leonardonogueira.idempotency.httpkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @Column(name = "idem_key", length = 100)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_body", length = 4000)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyKeyEntity() {}

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public String getStatus() { return status; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Integer getResponseCode() { return responseCode; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
