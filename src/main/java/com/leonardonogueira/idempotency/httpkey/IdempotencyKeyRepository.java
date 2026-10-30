package com.leonardonogueira.idempotency.httpkey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {

    /**
     * Reserva a Idempotency-Key atomicamente no banco com status IN_PROGRESS + Lease (locked_until) (Secoes 2.4 e 2.7).
     * Retorna 1 se reservou agora; 0 se a chave ja existia.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO idempotency_key (idem_key, request_hash, status, locked_until, created_at)
        VALUES (:key, :requestHash, 'IN_PROGRESS', :lockedUntil, CURRENT_TIMESTAMP)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int tryReserve(@Param("key") String key,
                   @Param("requestHash") String requestHash,
                   @Param("lockedUntil") Instant lockedUntil);

    /**
     * Recuperacao de Chave Orfa (Nivel Arquiteto - Secao 2.7, linha 447):
     * Se o servidor anterior morreu com kill -9 / queda de energia enquanto a chave estava IN_PROGRESS,
     * o locked_until expira. O proximo retry assume atomicamente a chave renovando o lease!
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE IdempotencyKeyEntity k
           SET k.lockedUntil = :newLockedUntil
         WHERE k.key = :key
           AND k.status = 'IN_PROGRESS'
           AND k.lockedUntil <= :now
        """)
    int tryTakeOverExpiredLease(@Param("key") String key,
                                @Param("now") Instant now,
                                @Param("newLockedUntil") Instant newLockedUntil);

    /**
     * Conclui a chave gravando o status HTTP e o JSON da resposta para devolver em futuros retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE IdempotencyKeyEntity k
           SET k.status = 'COMPLETED',
               k.responseCode = :responseCode,
               k.responseBody = :responseBody
         WHERE k.key = :key
        """)
    int complete(@Param("key") String key,
                 @Param("responseCode") int responseCode,
                 @Param("responseBody") String responseBody);
}
