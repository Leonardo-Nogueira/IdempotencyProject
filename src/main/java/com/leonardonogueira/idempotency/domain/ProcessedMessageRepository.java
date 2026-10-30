package com.leonardonogueira.idempotency.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessageEntity, ProcessedMessageId> {

    /**
     * Abordagem 2: Tabela de mensagens processadas (Inbox - Secao 2.2).
     * Retorna 1 se inseriu (primeira vez), 0 se ja existia (duplicata).
     * Atomico no banco via PRIMARY KEY (message_id, consumer), sem race condition!
     */
    @Modifying
    @Query(value = """
        INSERT INTO processed_message (message_id, consumer, processed_at)
        VALUES (:messageId, :consumer, CURRENT_TIMESTAMP)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int tryInsert(@Param("messageId") UUID messageId, @Param("consumer") String consumer);
}

