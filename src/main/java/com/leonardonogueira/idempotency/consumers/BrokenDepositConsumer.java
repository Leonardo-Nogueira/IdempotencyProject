package com.leonardonogueira.idempotency.consumers;

import com.leonardonogueira.idempotency.contracts.DepositMade;
import com.leonardonogueira.idempotency.domain.BankAccountRepository;
import com.leonardonogueira.idempotency.domain.ProcessedMessageEntity;
import com.leonardonogueira.idempotency.domain.ProcessedMessageId;
import com.leonardonogueira.idempotency.domain.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANTI-EXEMPLOS (Secoes 2.2 e 2.7):
 * Mostra na pratica o que acontece quando nao ha idempotencia ou quando se usa check-then-act.
 */
@Service
public class BrokenDepositConsumer {

    private static final Logger log = LoggerFactory.getLogger(BrokenDepositConsumer.class);
    public static final String CONSUMER_NAME = "broken-check-then-act-consumer";

    private final BankAccountRepository accountRepo;
    private final ProcessedMessageRepository processedRepo;

    public BrokenDepositConsumer(BankAccountRepository accountRepo,
                                 ProcessedMessageRepository processedRepo) {
        this.accountRepo = accountRepo;
        this.processedRepo = processedRepo;
    }

    /**
     * ❌ Anti-pattern 1: Sem nenhuma idempotencia em operacao cumulativa (saldo = saldo + valor).
     * Se o broker reentregar 10 vezes (at-least-once), o cliente ganha 10x o deposito!
     */
    @Transactional
    public void handleNaive(DepositMade event) {
        accountRepo.addToBalance(event.accountId(), event.amount());
        log.warn("[NAIVE] Deposito somado sem verificacao! messageId={}, valor={}", event.messageId(), event.amount());
    }

    /**
     * ❌ Anti-pattern 2 (Secao 2.7 - "Onde esta a race condition?"):
     * Check-Then-Act:
     *   if (!processedRepo.exists(id)) {
     *       doWork();
     *       processedRepo.save(id);
     *   }
     * Quando 2 ou mais threads chegam juntas (ex.: rebalanceamento ou retry simultaneo),
     * varias passam no existsById() antes que a primeira consiga salvar!
     */
    public boolean handleCheckThenAct(DepositMade event) {
        ProcessedMessageId id = new ProcessedMessageId(event.messageId(), CONSUMER_NAME);
        if (!processedRepo.existsById(id)) {
            // Simula pequena latencia entre o SELECT (check) e o UPDATE/INSERT (act)
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            accountRepo.addToBalance(event.accountId(), event.amount());
            try {
                processedRepo.saveAndFlush(new ProcessedMessageEntity(event.messageId(), CONSUMER_NAME));
            } catch (Exception ex) {
                // Mesmo que o save final falhe por PK duplicada, o addToBalance fora de transacao unificada
                // ou em estado inconsistente mostra o perigo do check-then-act!
                log.warn("[RACE CONDITION DETECTADA] Thread passou pelo existsById mas colidiu no save: {}", ex.getMessage());
            }
            return true;
        }
        return false;
    }
}

