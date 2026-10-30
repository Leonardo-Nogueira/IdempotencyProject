package com.leonardonogueira.idempotency.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerProjectionRepository extends JpaRepository<CustomerProjectionEntity, Long> {

    /**
     * Abordagem 1: Upsert naturalmente idempotente (Secao 2.2) compativel tanto com PostgreSQL (K8s) quanto H2.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO customer_projection (id, name, email, version, updated_at)
        VALUES (:id, :name, :email, :version, CURRENT_TIMESTAMP)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int tryInsertCustomer(@Param("id") Long id,
                          @Param("name") String name,
                          @Param("email") String email,
                          @Param("version") long version);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CustomerProjectionEntity c
           SET c.name = :name,
               c.email = :email,
               c.version = :version
         WHERE c.id = :id
        """)
    int updateUnconditionally(@Param("id") Long id,
                              @Param("name") String name,
                              @Param("email") String email,
                              @Param("version") long version);

    /**
     * Abordagem 3: Versionamento Monotonico (Secao 2.2).
     * Resolve DUPLICATAS (version == atual) e FORA DE ORDEM (version < atual).
     * Retorna 1 se aplicou a nova versao, ou 0 se o evento era obsoleto/duplicado.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CustomerProjectionEntity c
           SET c.name = :name,
               c.email = :email,
               c.version = :version
         WHERE c.id = :id AND c.version < :version
        """)
    int applyIfNewer(@Param("id") Long id,
                     @Param("name") String name,
                     @Param("email") String email,
                     @Param("version") long version);
}
