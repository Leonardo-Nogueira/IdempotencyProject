package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.domain.StockMovementRepository;
import com.leonardonogueira.idempotency.httpkey.HttpIdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CENARIO 6 (NIVEL ARQUITETO - Secao 2.7):
 * 1. Modelagem por Ledger Imutavel (stock_movement): Transforma operacao cumulativa (estoque/saldo)
 *    em operacao naturalmente idempotente usando o ID unico do lancamento como PRIMARY KEY!
 * 2. Recuperacao de Chave Presa em IN_PROGRESS (Lease / locked_until): Resolve o problema de
 *    o servidor cair (kill -9) depois de reservar a Idempotency-Key e antes de concluir!
 */
@RestController
@RequestMapping("/api")
public class Scenario6ImmutableLedgerController {

    private final StockMovementRepository stockMovementRepo;
    private final HttpIdempotencyService httpIdempotencyService;

    public Scenario6ImmutableLedgerController(StockMovementRepository stockMovementRepo,
                                              HttpIdempotencyService httpIdempotencyService) {
        this.stockMovementRepo = stockMovementRepo;
        this.httpIdempotencyService = httpIdempotencyService;
    }

    // =========================================================================
    // PARTE 1: LEDGER IMUTAVEL DE ESTOQUE (Sem tabela processed_message!)
    // =========================================================================

    public record StockMovementRequest(UUID movementId, String sku, int delta) {}

    @DeleteMapping("/stock/{sku}")
    public Map<String, Object> resetStock(@PathVariable String sku) {
        stockMovementRepo.deleteAllBySku(sku);
        return Map.of("sku", sku, "currentStock", 0);
    }

    @GetMapping("/stock/{sku}")
    public Map<String, Object> getStock(@PathVariable String sku) {
        int currentStock = stockMovementRepo.getCurrentStock(sku);
        return Map.of("sku", sku, "currentStock", currentStock);
    }

    /**
     * ✅ LEDGER IMUTAVEL:
     * Em vez de fazer UPDATE stock SET qty = qty + delta (que precisaria da tabela processed_message),
     * inserimos o lancamento na tabela stock_movement onde o proprio movementId e a PRIMARY KEY!
     * Resultado: Auditoria completa + Idempotencia Natural sem tabela extra!
     */
    @PostMapping("/fixed/stock/movements")
    public Map<String, Object> recordStockMovement(@RequestBody StockMovementRequest req) {
        int inserted = stockMovementRepo.tryInsertMovement(req.movementId(), req.sku(), req.delta());
        return Map.of(
                "movementId", req.movementId(),
                "sku", req.sku(),
                "delta", req.delta(),
                "applied", inserted == 1
        );
    }

    // =========================================================================
    // PARTE 2: RECUPERACAO DE CHAVE PRESA EM IN_PROGRESS (Lease / locked_until)
    // =========================================================================

    public record LeaseTestPayload(Long orderId, BigDecimal amount) {}

    /**
     * Passo A do teste: Simula que uma instancia do servidor reservou a Idempotency-Key
     * com um lease curto (ex.: 1 segundo) e "morreu" (kill -9) antes de concluir!
     */
    @PostMapping("/crash-simulation/stuck-key")
    public Map<String, Object> simulateCrashStuckKey(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(defaultValue = "1000") long leaseMillis,
            @RequestBody LeaseTestPayload payload) {

        httpIdempotencyService.simulateServerCrashLeavingStuckKey(
                idempotencyKey, payload, Duration.ofMillis(leaseMillis));

        return Map.of(
                "idempotencyKey", idempotencyKey,
                "status", "IN_PROGRESS (ORPHANED BY SERVER CRASH)",
                "leaseMillis", leaseMillis
        );
    }

    /**
     * Passo B do teste: Cliente faz retry na rota normal protegida por Idempotency-Key.
     * - Se tentar ANTES do lease expirar -> Recebe 409 Conflict ("Requisicao em andamento")
     * - Se tentar DEPOIS do lease expirar -> O servidor detecta que o locked_until venceu,
     *   assume a chave orfa (tryTakeOverExpiredLease) e conclui com 201 Created!
     */
    @PostMapping("/fixed/lease-recovery-payment")
    public ResponseEntity<String> paymentWithLeaseRecovery(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LeaseTestPayload payload) {

        return httpIdempotencyService.execute(idempotencyKey, payload, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(new LinkedHashMap<>(Map.of(
                        "recoveredFromOrphanedKey", true,
                        "paymentId", "pay-recovered-" + UUID.randomUUID(),
                        "orderId", payload.orderId(),
                        "amount", payload.amount(),
                        "status", "COMPLETED"
                )))
        );
    }
}

