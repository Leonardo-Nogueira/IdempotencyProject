package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.httpkey.HttpIdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CENARIO 4: API HTTP POST na Borda com Idempotency-Key (Padrao Stripe / Mercado Pago - Secao 2.4)
 * Script k6 correspondente: k6/04-http-idempotency-key.js
 *
 * Compara:
 * - ❌ /api/wrong/transfers (POST sem Idempotency-Key: cada retry de rede cria uma nova transferencia)
 * - ✅ /api/fixed/transfers (POST com Idempotency-Key: reserva atomica + SHA-256 do payload + cache da resposta)
 */
@RestController
@RequestMapping("/api")
public class Scenario4HttpIdempotencyKeyController {

    private final HttpIdempotencyService httpIdempotencyService;

    public Scenario4HttpIdempotencyKeyController(HttpIdempotencyService httpIdempotencyService) {
        this.httpIdempotencyService = httpIdempotencyService;
    }

    public record TransferRequest(Long fromAccount, Long toAccount, BigDecimal amount) {}

    /**
     * ❌ ERRO: POST sem Idempotency-Key. Se o cliente sofrer timeout de leitura e retentar,
     * o servidor executa uma segunda transferencia e gera um novo transferId!
     */
    @PostMapping("/wrong/transfers")
    public ResponseEntity<Map<String, Object>> wrongCreateTransfer(@RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "transferId", UUID.randomUUID().toString(),
                "status", "COMPLETED",
                "amount", request.amount()
        ));
    }

    /**
     * ✅ CORRECAO: POST protegido pelo header Idempotency-Key.
     * - 1a chamada: Reserva a chave, executa e grava o status + JSON (201 Created)
     * - Retry com mesma chave e mesmo payload: Devolve o JSON salvo com o MESMO transferId (201 Created)
     * - Chamada concorrente enquanto a 1a ainda esta IN_PROGRESS: Retorna 409 Conflict
     * - Mesma chave reutilizada com payload diferente: Retorna 422 Unprocessable Entity
     */
    @PostMapping("/fixed/transfers")
    public ResponseEntity<String> fixedCreateTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferRequest request) {

        return httpIdempotencyService.execute(idempotencyKey, request, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(new LinkedHashMap<>(Map.of(
                        "transferId", UUID.randomUUID().toString(),
                        "status", "COMPLETED",
                        "amount", request.amount()
                )))
        );
    }
}

