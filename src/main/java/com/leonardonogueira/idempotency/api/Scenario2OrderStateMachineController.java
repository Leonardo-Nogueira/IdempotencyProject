package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.consumers.Approach1NaturalConsumer;
import com.leonardonogueira.idempotency.contracts.PaymentApproved;
import com.leonardonogueira.idempotency.domain.OrderEntity;
import com.leonardonogueira.idempotency.domain.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * CENARIO 2: Abordagem 1 - Operacao Naturalmente Idempotente com Maquina de
 * Estados (PENDING -> PAID)
 * Script k6 correspondente: k6/02-order-state-machine.js
 *
 * Mostra como operacoes que definem ESTADO condicional (WHERE id = :id AND
 * status = 'PENDING')
 * sao naturalmente idempotentes sem precisar de tabela extra de mensagens
 * processadas.
 */
@RestController
@RequestMapping("/api")
public class Scenario2OrderStateMachineController {

    private final Approach1NaturalConsumer approach1Consumer;
    private final OrderRepository orderRepo;

    public Scenario2OrderStateMachineController(Approach1NaturalConsumer approach1Consumer,
            OrderRepository orderRepo) {
        this.approach1Consumer = approach1Consumer;
        this.orderRepo = orderRepo;
    }

    public record CreateOrderRequest(Long id, BigDecimal amount) {
    }

    @PostMapping("/orders")
    public OrderEntity createOrResetOrder(@RequestBody CreateOrderRequest req) {
        return orderRepo.saveAndFlush(new OrderEntity(req.id(), req.amount(), "PENDING"));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderEntity> getOrder(@PathVariable Long id) {
        return orderRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * UPDATE orders SET status = 'PAID' WHERE id = :id AND status = 'PENDING'.
     * Mesmo com 30 VUs simultaneos no k6, apenas 1 retorna transitionedToPaid =
     * true!
     */
    @PostMapping("/fixed/orders/pay")
    public Map<String, Object> fixedOrderPay(@RequestBody PaymentApproved event) {
        boolean transitioned = approach1Consumer.handlePaymentApproved(event);
        return Map.of("transitionedToPaid", transitioned, "orderId", event.orderId());
    }
}
