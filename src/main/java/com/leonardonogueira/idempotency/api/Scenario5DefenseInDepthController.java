package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.domain.OrderEntity;
import com.leonardonogueira.idempotency.domain.OrderRepository;
import com.leonardonogueira.idempotency.httpkey.HttpIdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CENARIO 5 (PADRAO OURO DE PRODUCAO): Defesa em Profundidade (Camada 1 +
 * Camada 2)
 * Script k6 correspondente: k6/05-defense-in-depth-checkout.js
 *
 * Une:
 * - Camada 1 (Idempotencia Tecnica na Borda HTTP): Header Idempotency-Key +
 * SHA-256 do payload
 * -> Resolve retries automaticos de rede/timeout devolvendo o EXATO mesmo JSON
 * (201 Created).
 * - Camada 2 (Idempotencia de Negocio no Dominio): Maquina de Estados (WHERE id
 * = :id AND status = 'PENDING')
 * -> Resolve o caso em que o usuario da F5 / abre outra aba e envia uma NOVA
 * Idempotency-Key
 * para tentar pagar um pedido que JA foi pago!
 */
@RestController
@RequestMapping("/api")
public class Scenario5DefenseInDepthController {

    private final HttpIdempotencyService httpIdempotencyService;
    private final OrderRepository orderRepo;

    public Scenario5DefenseInDepthController(HttpIdempotencyService httpIdempotencyService,
            OrderRepository orderRepo) {
        this.httpIdempotencyService = httpIdempotencyService;
        this.orderRepo = orderRepo;
    }

    public record CheckoutRequest(Long orderId, BigDecimal amount, String paymentMethod) {
    }

    /**
     * INCOMPLETO: Usa APENAS a Camada 1 (Idempotency-Key), sem validar a Maquina de
     * Estados no Dominio.
     * Problema: Se o cliente der F5 na pagina e o front-end gerar uma NOVA
     * Idempotency-Key (key-BBB)
     * para o mesmo orderId, a Camada 1 deixa passar e o cliente e cobrado DUAS
     * VEZES!
     */
    @PostMapping("/wrong/checkout-only-http-key")
    public ResponseEntity<String> wrongCheckoutOnlyHttpKey(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CheckoutRequest request) {

        return httpIdempotencyService.execute(idempotencyKey, request, () -> {
            // Erro de negocio: Muda para PAID sem checar se ja estava PAID (nao olha WHERE
            // status = 'PENDING')
            OrderEntity order = orderRepo.findById(request.orderId()).orElseThrow();
            order.setStatus("PAID");
            orderRepo.saveAndFlush(order);

            return ResponseEntity.status(HttpStatus.CREATED).body(new LinkedHashMap<>(Map.of(
                    "layer", "❌ Apenas Camada 1 (Cobrou sem checar se o pedido ja estava PAID!)",
                    "paymentId", "pay-" + UUID.randomUUID(),
                    "orderId", request.orderId(),
                    "status", "PAID")));
        });
    }

    /**
     * ✅ PADRAO OURO (Camada 1 + Camada 2 Juntas):
     * 1. Camada 1 (HttpIdempotencyService):
     * - Se for retry de rede com a MESMA Idempotency-Key -> devolve o comprovante
     * salvo (201 Created) sem reexecutar.
     * - Se reutilizar a mesma Idempotency-Key mudando o valor -> bloqueia com 422
     * Unprocessable Entity.
     * 2. Camada 2 (OrderRepository.markAsPaidIfPending):
     * - Se o cliente gerou uma NOVA Idempotency-Key (ex.: deu F5 ou abriu outra
     * aba) para o mesmo orderId,
     * o UPDATE ... WHERE id = :id AND status = 'PENDING' retorna 0 linhas alteradas
     * e barra a segunda cobranca com 409 Conflict (ORDER_ALREADY_PAID)!
     */
    @PostMapping("/fixed/checkout")
    public ResponseEntity<String> fixedCheckoutDefenseInDepth(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CheckoutRequest request) {

        // CAMADA 1: Protecao Tecnica HTTP (Idempotency-Key + Hash SHA-256 + Cache da
        // Resposta)
        return httpIdempotencyService.execute(idempotencyKey, request, () -> {

            // CAMADA 2: Protecao de Negocio no Dominio (Maquina de Estados Atomica no
            // Banco)
            int updatedRows = orderRepo.markAsPaidIfPending(request.orderId());
            if (updatedRows == 0) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new LinkedHashMap<>(Map.of(
                        "error", "ORDER_ALREADY_PAID",
                        "message",
                        "Bloqueado pela Camada 2 (Dominio): O pedido #" + request.orderId()
                                + " ja foi pago ou nao esta PENDING!",
                        "orderId", request.orderId())));
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(new LinkedHashMap<>(Map.of(
                    "layer", "✅ Camada 1 (Idempotency-Key) + Camada 2 (State Machine) aprovadas!",
                    "paymentId", "pay-" + UUID.randomUUID(),
                    "orderId", request.orderId(),
                    "amountCharged", request.amount(),
                    "status", "PAID")));
        });
    }
}
