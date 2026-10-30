package com.leonardonogueira.idempotency.httpkey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Implementacao completa do padrao Idempotency-Key em APIs HTTP + Lease de
 * Recuperacao (Secoes 2.4 e 2.7):
 * 1. Cliente gera UUID e envia no header Idempotency-Key.
 * 2. Servidor reserva a chave atomicamente (INSERT ... ON CONFLICT DO NOTHING)
 * com SHA-256 do payload + locked_until (Lease).
 * 3. Se reservou (1), processa e grava a resposta (COMPLETED).
 * 4. Se ja existia (0):
 * - Hash diferente -> 422 Unprocessable Entity ("Chave reutilizada com payload
 * diferente")
 * - Status IN_PROGRESS com Lease VALIDO (locked_until > now) -> 409 Conflict
 * ("Requisicao em andamento")
 * - Status IN_PROGRESS com Lease EXPIRADO (locked_until <= now, servidor
 * anterior caiu!) -> Assume o lease atomicamente e reprocessa!
 * - Status COMPLETED -> Devolve exatamente a mesma resposta salva!
 */
@Service
public class HttpIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(HttpIdempotencyService.class);
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper objectMapper;

    public HttpIdempotencyService(IdempotencyKeyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> execute(String key, Object payload, Supplier<ResponseEntity<?>> action) {
        return executeWithLease(key, payload, DEFAULT_LEASE_DURATION, action);
    }

    public ResponseEntity<String> executeWithLease(String key,
                                                   Object payload,
                                                   Duration leaseDuration,
                                                   Supplier<ResponseEntity<?>> action) {
        String requestHash = sha256(payload);
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(leaseDuration);

        int inserted = repo.tryReserve(key, requestHash, lockedUntil);
        if (inserted == 0) {
            IdempotencyKeyEntity existing = repo.findById(key).orElseThrow();

            // Regra 6 da Secao 2.4: Chave reutilizada com payload diferente -> 422
            if (!existing.getRequestHash().equals(requestHash)) {
                return jsonResponse(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        toJson(Map.of("error", "Chave reutilizada com payload diferente", "idempotencyKey", key)));
            }

            // Regra 5 da Secao 2.4 + Lease da Secao 2.7 (linha 447):
            if (IdempotencyKeyEntity.STATUS_IN_PROGRESS.equals(existing.getStatus())) {
                // Se o lease ainda NAO expirou, a 1a requisicao realmente esta rodando -> 409 Conflict
                if (existing.getLockedUntil().isAfter(now)) {
                    return jsonResponse(HttpStatus.CONFLICT.value(),
                            toJson(Map.of(
                                    "error", "Requisicao em andamento",
                                    "idempotencyKey", key,
                                    "lockedUntil", existing.getLockedUntil().toString()
                            )));
                }

                // O lease EXPIROU (o servidor anterior sofreu crash/kill -9 antes de concluir!).
                // Tentamos assumir o lease atomicamente no banco:
                int takenOver = repo.tryTakeOverExpiredLease(key, now, lockedUntil);
                if (takenOver == 0) {
                    // Outra thread acabou de assumir no mesmo milissegundo -> 409 Conflict
                    return jsonResponse(HttpStatus.CONFLICT.value(),
                            toJson(Map.of("error", "Requisicao em andamento (lease assumido por outra thread)", "idempotencyKey", key)));
                }
                log.warn("[IDEMPOTENCY LEASE RECOVERY] Chave '{}' estava presa em IN_PROGRESS com lease expirado! Assumindo processamento.", key);
                // Segue abaixo para executar o action.get() e concluir a chave!
            } else {
                // Regra 4 da Secao 2.4: Chave existe e esta COMPLETED -> devolve a resposta salva
                return jsonResponse(existing.getResponseCode(), existing.getResponseBody());
            }
        }

        try {
            ResponseEntity<?> response = action.get();
            String bodyJson = toJson(response.getBody());
            repo.complete(key, response.getStatusCode().value(), bodyJson);
            return jsonResponse(response.getStatusCode().value(), bodyJson);
        } catch (RuntimeException ex) {
            // Erros transitorios (5xx) liberam a chave imediatamente para permitir retry (Secao 2.7)
            repo.deleteById(key);
            throw ex;
        }
    }

    /**
     * Metodo auxiliar para simular no laboratorio um crash do servidor (kill -9)
     * que deixou uma chave orfa presa em IN_PROGRESS no banco!
     */
    public void simulateServerCrashLeavingStuckKey(String key, Object payload, Duration leaseDuration) {
        String requestHash = sha256(payload);
        repo.tryReserve(key, requestHash, Instant.now().plus(leaseDuration));
        log.warn("[CRASH SIMULADO] Servidor 'morreu' deixando a chave '{}' presa em IN_PROGRESS!", key);
    }

    public String sha256(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash do payload", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return obj instanceof String s ? s : objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao serializar resposta", e);
        }
    }

    private ResponseEntity<String> jsonResponse(int status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
