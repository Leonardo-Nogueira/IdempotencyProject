package com.leonardonogueira.idempotency.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

public interface BankAccountRepository extends JpaRepository<BankAccountEntity, Long> {

    /**
     * Incremento atomico de saldo no banco.
     * ATENCAO: Como e uma operacao cumulativa (+ amount), so e idempotente quando
     * executada na MESMA transacao da tabela processed_message (Abordagem 2).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BankAccountEntity a SET a.balance = a.balance + :amount WHERE a.id = :accountId")
    int addToBalance(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);
}

