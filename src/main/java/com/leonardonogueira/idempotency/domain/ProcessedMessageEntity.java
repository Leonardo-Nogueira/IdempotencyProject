package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_message")
public class ProcessedMessageEntity {

    @EmbeddedId
    private ProcessedMessageId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedMessageEntity() {}

    public ProcessedMessageEntity(UUID messageId, String consumer) {
        this.id = new ProcessedMessageId(messageId, consumer);
        this.processedAt = Instant.now();
    }

    public ProcessedMessageId getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}

