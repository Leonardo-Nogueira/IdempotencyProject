package com.leonardonogueira.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProcessedMessageId implements Serializable {

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    protected ProcessedMessageId() {}

    public ProcessedMessageId(UUID messageId, String consumer) {
        this.messageId = messageId;
        this.consumer = consumer;
    }

    public UUID getMessageId() { return messageId; }
    public String getConsumer() { return consumer; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedMessageId that)) return false;
        return Objects.equals(messageId, that.messageId) && Objects.equals(consumer, that.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumer);
    }
}

