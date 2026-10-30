package com.leonardonogueira.idempotency.consumers;

import com.leonardonogueira.idempotency.contracts.CustomerUpdated;
import com.leonardonogueira.idempotency.contracts.PaymentApproved;
import com.leonardonogueira.idempotency.domain.CustomerProjectionRepository;
import com.leonardonogueira.idempotency.domain.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABORDAGEM 1: Operacao Naturalmente Idempotente + Maquina de Estados (Secoes 2.2 e 2.3).
 * - Define ESTADO em vez de incrementar (SET status = 'PAID' WHERE status = 'PENDING').
 * - Usa UPSERT em vez de INSERT simples.
 * - Custo: Nenhum (nao precisa de tabela extra de mensagens processadas).
 */
@Service
public class Approach1NaturalConsumer {

    private static final Logger log = LoggerFactory.getLogger(Approach1NaturalConsumer.class);

    private final OrderRepository orderRepo;
    private final CustomerProjectionRepository customerRepo;

    public Approach1NaturalConsumer(OrderRepository orderRepo,
                                    CustomerProjectionRepository customerRepo) {
        this.orderRepo = orderRepo;
        this.customerRepo = customerRepo;
    }

    /**
     * Maquina de estados idempotente:
     * So transiciona PENDING -> PAID uma unica vez.
     * @return true se alterou o estado agora; false se ja estava PAID (ou estado invalido).
     */
    @Transactional
    public boolean handlePaymentApproved(PaymentApproved event) {
        int updatedRows = orderRepo.markAsPaidIfPending(event.orderId());
        if (updatedRows == 0) {
            log.info("[ABORDAGEM 1 - STATE MACHINE] Evento duplicado ou estado invalido para orderId={}. Ignorando.", event.orderId());
            return false;
        }
        log.info("[ABORDAGEM 1 - STATE MACHINE] Pedido {} marcado como PAID com sucesso!", event.orderId());
        return true;
    }

    /**
     * Upsert naturalmente idempotente:
     * Inserir ou atualizar com o mesmo estado N vezes produz exatamente a mesma linha final.
     */
    @Transactional
    public void handleCustomerUpsert(CustomerUpdated event) {
        int inserted = customerRepo.tryInsertCustomer(event.customerId(), event.name(), event.email(), event.version());
        if (inserted == 0) {
            customerRepo.updateUnconditionally(event.customerId(), event.name(), event.email(), event.version());
        }
        log.info("[ABORDAGEM 1 - UPSERT] Projecao do cliente {} sincronizada.", event.customerId());
    }
}

