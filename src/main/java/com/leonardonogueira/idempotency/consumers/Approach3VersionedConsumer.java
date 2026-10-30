package com.leonardonogueira.idempotency.consumers;

import com.leonardonogueira.idempotency.contracts.CustomerUpdated;
import com.leonardonogueira.idempotency.domain.CustomerProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABORDAGEM 3: Versionamento Monotonico (Secao 2.2).
 * O evento carrega uma versao/sequencia gerada na fonte.
 * O consumidor descarta qualquer evento com version <= versao ja aplicada.
 *
 * Resolve DOIS problemas de uma vez:
 * 1. Mensagens duplicadas (mesma versao chega 2x)
 * 2. Mensagens fora de ordem (v3 chega antes da v2 -> a v2 e descartada!)
 */
@Service
public class Approach3VersionedConsumer {

    private static final Logger log = LoggerFactory.getLogger(Approach3VersionedConsumer.class);

    private final CustomerProjectionRepository repo;

    public Approach3VersionedConsumer(CustomerProjectionRepository repo) {
        this.repo = repo;
    }

    /**
     * Aplica a atualizacao somente se event.version() > version atual no banco.
     *
     * @return true se o evento atualizou o registro; false se foi ignorado por ser
     *         duplicado ou obsoleto.
     */
    @Transactional
    public boolean handleCustomerUpdated(CustomerUpdated event) {
        int inserted = repo.tryInsertCustomer(
                event.customerId(), event.name(), event.email(), event.version());
        if (inserted == 1) {
            log.info("[ABORDAGEM 3 - VERSIONAMENTO] Cliente {} criado ja na versao v{}",
                    event.customerId(), event.version());
            return true;
        }

        int rows = repo.applyIfNewer(event.customerId(), event.name(), event.email(), event.version());
        if (rows == 0) {
            log.info("[ABORDAGEM 3 - VERSIONAMENTO] Evento obsoleto ou duplicado (v{}), ignorado para customerId={}",
                    event.version(), event.customerId());
            return false;
        }

        log.info("[ABORDAGEM 3 - VERSIONAMENTO] Cliente {} atualizado para v{} ({} / {})",
                event.customerId(), event.version(), event.name(), event.email());
        return true;
    }
}
