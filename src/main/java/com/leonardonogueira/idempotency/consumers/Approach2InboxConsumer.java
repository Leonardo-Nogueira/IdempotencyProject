package com.leonardonogueira.idempotency.consumers;

import com.leonardonogueira.idempotency.contracts.DepositMade;
import com.leonardonogueira.idempotency.domain.BankAccountRepository;
import com.leonardonogueira.idempotency.domain.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABORDAGEM 2: Tabela de Mensagens Processadas / Transactional Inbox (Secao 2.2).
 * Ideal para operacoes CUMULATIVAS (saldo, estoque, contadores).
 *
 * Regra de Ouro:
 * 1. Reservar o messageId com INSERT ... ON CONFLICT DO NOTHING (o banco arbitra a concorrencia).
 * 2. Executar o efeito colateral na MESMA transacao (@Transactional).
 * 3. Se o passo 2 falhar e lancar excecao, o rollback desfaz tambem o passo 1!
 */
@Service
public class Approach2InboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(Approach2InboxConsumer.class);
    public static final String CONSUMER_NAME = "account-service.deposit";

    private final ProcessedMessageRepository processedRepo;
    private final BankAccountRepository accountRepo;

    public Approach2InboxConsumer(ProcessedMessageRepository processedRepo,
                                  BankAccountRepository accountRepo) {
        this.processedRepo = processedRepo;
        this.accountRepo = accountRepo;
    }

    /**
     * ✅ Implementacao correta e atomica (Effectively-Once = At-Least-Once + Idempotencia).
     * @return true se processou pela primeira vez; false se era duplicata e foi ignorada.
     */
    @Transactional
    public boolean handleDeposit(DepositMade event) {
        // 1. Reserva o ID atomicamente. O banco arbitra a concorrencia via UNIQUE/PK.
        int inserted = processedRepo.tryInsert(event.messageId(), CONSUMER_NAME);
        if (inserted == 0) {
            log.info("[ABORDAGEM 2 - INBOX] Duplicata detectada, ignorando messageId={}", event.messageId());
            return false;
        }

        // 2. Efeito colateral na MESMA transacao
        accountRepo.addToBalance(event.accountId(), event.amount());
        log.info("[ABORDAGEM 2 - INBOX] Deposito de {} aplicado na conta {} (messageId={})",
                event.amount(), event.accountId(), event.messageId());
        return true;
    }

    /**
     * ✅ Prova de Rollback Transacional (Secao 2.7):
     * Se o efeito colateral falhar e lancar RuntimeException, o rollback desfaz tambem
     * a insercao na processed_message, permitindo que a reentrega do broker funcione depois!
     */
    @Transactional
    public void handleDepositWithSimulatedFailure(DepositMade event) {
        int inserted = processedRepo.tryInsert(event.messageId(), CONSUMER_NAME);
        if (inserted == 0) {
            return;
        }
        // Simula falha antes de concluir o trabalho:
        throw new IllegalStateException("Falha simulada no banco/servico durante o processamento do deposito!");
    }

    /**
     * ❌ ARMADILHA MORTAL (Secao 2.7 - "capturar a excecao e nao relancar"):
     * Se voce engolir a excecao no catch dentro de @Transactional, o Spring commita a transacao!
     * Resultado: o messageId fica gravado como processado na processed_message, mas o saldo NUNCA entrou!
     */
    @Transactional
    public void handleDepositSwallowingExceptionTrap(DepositMade event) {
        int inserted = processedRepo.tryInsert(event.messageId(), CONSUMER_NAME);
        if (inserted == 0) {
            return;
        }
        try {
            throw new RuntimeException("Erro ao creditar conta");
        } catch (Exception ex) {
            log.error("[ARMADILHA] Excecao engolida! A marca em processed_message sera commitada e o efeito foi perdido!", ex);
            // ❌ Nao relancou: transacao commita a marca sem o efeito!
        }
    }
}

