package com.leonardonogueira.idempotency;

import com.leonardonogueira.idempotency.api.Scenario4HttpIdempotencyKeyController.TransferRequest;
import com.leonardonogueira.idempotency.consumers.Approach1NaturalConsumer;
import com.leonardonogueira.idempotency.consumers.Approach2InboxConsumer;
import com.leonardonogueira.idempotency.consumers.Approach3VersionedConsumer;
import com.leonardonogueira.idempotency.consumers.BrokenDepositConsumer;
import com.leonardonogueira.idempotency.contracts.CustomerUpdated;
import com.leonardonogueira.idempotency.contracts.DepositMade;
import com.leonardonogueira.idempotency.contracts.PaymentApproved;
import com.leonardonogueira.idempotency.domain.BankAccountEntity;
import com.leonardonogueira.idempotency.domain.BankAccountRepository;
import com.leonardonogueira.idempotency.domain.CustomerProjectionEntity;
import com.leonardonogueira.idempotency.domain.CustomerProjectionRepository;
import com.leonardonogueira.idempotency.domain.OrderEntity;
import com.leonardonogueira.idempotency.domain.OrderRepository;
import com.leonardonogueira.idempotency.domain.ProcessedMessageId;
import com.leonardonogueira.idempotency.domain.ProcessedMessageRepository;
import com.leonardonogueira.idempotency.domain.StockMovementRepository;
import com.leonardonogueira.idempotency.httpkey.HttpIdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdempotencyApplicationTests {

    @Autowired
    private BrokenDepositConsumer brokenConsumer;

    @Autowired
    private Approach1NaturalConsumer approach1Consumer;

    @Autowired
    private Approach2InboxConsumer approach2Consumer;

    @Autowired
    private Approach3VersionedConsumer approach3Consumer;

    @Autowired
    private HttpIdempotencyService httpIdempotencyService;

    @Autowired
    private BankAccountRepository accountRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private CustomerProjectionRepository customerRepo;

    @Autowired
    private ProcessedMessageRepository processedRepo;

    @Autowired
    private StockMovementRepository stockMovementRepo;

    @Test
    @Order(1)
    @DisplayName("1. [ANTI-EXEMPLO] Operacao cumulativa sem idempotencia multiplica o saldo em reentregas (10x R$ 100 = R$ 1000)")
    void semIdempotencia_duplicaSaldoEmReentregas() {
        Long accountId = 101L;
        accountRepo.saveAndFlush(new BankAccountEntity(accountId, "Cliente Sem Protecao", BigDecimal.ZERO));

        DepositMade event = new DepositMade(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
        var pool = Executors.newFixedThreadPool(10);

        IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> brokenConsumer.handleNaive(event), pool))
                .toList()
                .forEach(CompletableFuture::join);
        pool.shutdown();

        BigDecimal balance = accountRepo.findById(accountId).orElseThrow().getBalance();
        // Sem idempotencia, o cliente recebeu R$ 1.000,00 em vez de R$ 100,00!
        assertThat(balance).isEqualByComparingTo("1000.00");
    }

    @Test
    @Order(2)
    @DisplayName("2. [ANTI-EXEMPLO] Check-Then-Act (exists + save) sofre Race Condition com threads concorrentes")
    void checkThenAct_sofreRaceConditionEmConcorrencia() {
        Long accountId = 102L;
        accountRepo.saveAndFlush(new BankAccountEntity(accountId, "Cliente Check-Then-Act", BigDecimal.ZERO));

        DepositMade event = new DepositMade(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
        var pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);

        var futures = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    brokenConsumer.handleCheckThenAct(event);
                }, pool))
                .toList();

        gate.countDown(); // Libera todas as 10 threads simultaneamente no existsById()
        futures.forEach(CompletableFuture::join);
        pool.shutdown();

        BigDecimal balance = accountRepo.findById(accountId).orElseThrow().getBalance();
        // Como varias threads passaram no existsById() ao mesmo tempo, o saldo passa de 100.00!
        assertThat(balance).isGreaterThan(new BigDecimal("100.00"));
    }

    @Test
    @Order(3)
    @DisplayName("3. [ABORDAGEM 1] Naturalmente Idempotente: Maquina de Estados (PENDING -> PAID) e Upsert")
    void abordagem1_maquinaDeEstadosEUpsert_aplicamApenasUmaVez() {
        Long orderId = 201L;
        orderRepo.saveAndFlush(new OrderEntity(orderId, new BigDecimal("350.00"), "PENDING"));

        PaymentApproved event = new PaymentApproved(UUID.randomUUID(), orderId, 1L);
        AtomicInteger transitions = new AtomicInteger(0);
        var pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);

        var futures = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (approach1Consumer.handlePaymentApproved(event)) {
                        transitions.incrementAndGet();
                    }
                }, pool))
                .toList();

        gate.countDown();
        futures.forEach(CompletableFuture::join);
        pool.shutdown();

        // Exatamente 1 thread conseguiu fazer a transicao PENDING -> PAID!
        assertThat(transitions.get()).isEqualTo(1);
        assertThat(orderRepo.findById(orderId).orElseThrow().getStatus()).isEqualTo("PAID");

        // Testando tambem o UPSERT repetido:
        CustomerUpdated customerEvent = new CustomerUpdated(UUID.randomUUID(), 201L, 1L, "Maria Silva", "maria@email.com");
        approach1Consumer.handleCustomerUpsert(customerEvent);
        approach1Consumer.handleCustomerUpsert(customerEvent);
        assertThat(customerRepo.findById(201L).orElseThrow().getName()).isEqualTo("Maria Silva");
    }

    @Test
    @Order(4)
    @DisplayName("4. [ABORDAGEM 2] Tabela Inbox (ON CONFLICT DO NOTHING + @Transactional): 10 duplicatas concorrentes aplicam APENAS UMA VEZ")
    void abordagem2_duplicatasConcorrentes_aplicaApenasUmaVez() {
        Long accountId = 301L;
        accountRepo.saveAndFlush(new BankAccountEntity(accountId, "Cliente Inbox Idempotente", BigDecimal.ZERO));

        var event = new DepositMade(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
        var pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger timesApplied = new AtomicInteger(0);

        var futures = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (approach2Consumer.handleDeposit(event)) {
                        timesApplied.incrementAndGet();
                    }
                }, pool))
                .toList();

        gate.countDown();
        futures.forEach(CompletableFuture::join);
        pool.shutdown();

        // Prova da Secao 2.6: Mesmo sob 10 threads simultaneas, o saldo final e exatamente 100.00!
        assertThat(timesApplied.get()).isEqualTo(1);
        assertThat(accountRepo.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @Order(5)
    @DisplayName("5. [ABORDAGEM 2 - TRANSACAO] Rollback desfaz a marca em caso de erro, mas engolir excecao perde a mensagem!")
    void abordagem2_rollbackDesfazMarca_eEngolirExcecaoPerdeMensagem() {
        Long accountId = 302L;
        accountRepo.saveAndFlush(new BankAccountEntity(accountId, "Cliente Teste Transacao", BigDecimal.ZERO));

        // Parte A: Quando a excecao e lancada, o @Transactional faz rollback TAMBEM da tabela processed_message
        UUID messageIdRollback = UUID.randomUUID();
        DepositMade eventRetryable = new DepositMade(messageIdRollback, accountId, new BigDecimal("50.00"));

        assertThatThrownBy(() -> approach2Consumer.handleDepositWithSimulatedFailure(eventRetryable))
                .isInstanceOf(IllegalStateException.class);

        // Verifica que a marca NAO ficou presa no banco apos a falha:
        assertThat(processedRepo.existsById(new ProcessedMessageId(messageIdRollback, Approach2InboxConsumer.CONSUMER_NAME)))
                .isFalse();

        // Agora o broker reentrega a mesma mensagem e ela e processada com sucesso!
        boolean appliedOnRetry = approach2Consumer.handleDeposit(eventRetryable);
        assertThat(appliedOnRetry).isTrue();
        assertThat(accountRepo.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("50.00");

        // Parte B: Armadilha mortal da Secao 2.7 (engolir a excecao no catch sem relancar)
        UUID messageIdSwallowed = UUID.randomUUID();
        DepositMade eventLost = new DepositMade(messageIdSwallowed, accountId, new BigDecimal("200.00"));

        approach2Consumer.handleDepositSwallowingExceptionTrap(eventLost);

        // A marca foi commitada por engano! Quando o broker reentrega, o consumidor acha que ja processou e ignora:
        assertThat(processedRepo.existsById(new ProcessedMessageId(messageIdSwallowed, Approach2InboxConsumer.CONSUMER_NAME)))
                .isTrue();
        boolean appliedAfterTrap = approach2Consumer.handleDeposit(eventLost);
        assertThat(appliedAfterTrap).isFalse();
        // O saldo continuou 50.00 (os 200.00 foram perdidos para sempre!)
        assertThat(accountRepo.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @Order(6)
    @DisplayName("6. [ABORDAGEM 3] Versionamento Monotonico: Resolve duplicatas E mensagens fora de ordem (v1 -> v3 -> v2)")
    void abordagem3_versionamento_ignoraEventosForaDeOrdemEDuplicados() {
        Long customerId = 401L;

        CustomerUpdated v1 = new CustomerUpdated(UUID.randomUUID(), customerId, 1L, "Leo V1", "v1@email.com");
        CustomerUpdated v2 = new CustomerUpdated(UUID.randomUUID(), customerId, 2L, "Leo V2 (Atrasado)", "v2@email.com");
        CustomerUpdated v3 = new CustomerUpdated(UUID.randomUUID(), customerId, 3L, "Leo V3 (Atual)", "v3@email.com");

        // 1. Chega v1 -> aplica
        assertThat(approach3Consumer.handleCustomerUpdated(v1)).isTrue();
        // 2. Chega v3 antes do v2 -> aplica v3
        assertThat(approach3Consumer.handleCustomerUpdated(v3)).isTrue();
        // 3. Chega v2 atrasado (fora de ordem!) -> deve ser IGNORADO (version < 3)
        assertThat(approach3Consumer.handleCustomerUpdated(v2)).isFalse();
        // 4. Chega v3 duplicado -> deve ser IGNORADO (version == 3)
        assertThat(approach3Consumer.handleCustomerUpdated(v3)).isFalse();

        CustomerProjectionEntity projection = customerRepo.findById(customerId).orElseThrow();
        assertThat(projection.getVersion()).isEqualTo(3L);
        assertThat(projection.getName()).isEqualTo("Leo V3 (Atual)");
        assertThat(projection.getEmail()).isEqualTo("v3@email.com");
    }

    @Test
    @Order(7)
    @DisplayName("7. [HTTP IDEMPOTENCY-KEY] Devolve resposta salva no retry e bloqueia payload divergente com 422")
    void httpIdempotencyKey_retornaRespostaSalvaEBloqueiaPayloadDiferente() {
        String key = UUID.randomUUID().toString();
        TransferRequest req1 = new TransferRequest(1L, 2L, new BigDecimal("150.00"));
        AtomicInteger executions = new AtomicInteger(0);

        // 1a chamada: executa de fato e salva resposta
        ResponseEntity<String> firstResponse = httpIdempotencyService.execute(key, req1, () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("transferId", "tx-999", "amount", req1.amount()));
        });

        // 2a chamada (retry do cliente com a mesma chave e mesmo payload): NAO executa de novo e devolve o mesmo body
        ResponseEntity<String> retryResponse = httpIdempotencyService.execute(key, req1, () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("transferId", "tx-outro", "amount", req1.amount()));
        });

        assertThat(executions.get()).isEqualTo(1);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retryResponse.getBody()).isEqualTo(firstResponse.getBody());

        // 3a chamada: Cliente tenta reutilizar a MESMA Idempotency-Key com valor diferente -> 422 Unprocessable Entity
        TransferRequest reqFraudOrBug = new TransferRequest(1L, 2L, new BigDecimal("9999.00"));
        ResponseEntity<String> mismatchResponse = httpIdempotencyService.execute(key, reqFraudOrBug, () ->
                ResponseEntity.ok(Map.of("status", "nao-deveria-executar"))
        );

        assertThat(mismatchResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatchResponse.getBody()).contains("Chave reutilizada com payload diferente");
    }

    @Test
    @Order(8)
    @DisplayName("8. [ARQUITETO - LEDGER IMUTAVEL & LEASE] Estoque por Ledger Imutavel + Recuperacao de Chave IN_PROGRESS Orfa")
    void cenario6_ledgerImutavelELeaseDeChaveOrfa() throws InterruptedException {
        // Parte 1: Ledger Imutavel de Estoque (10 threads tentando dar baixa do mesmo movimento -2)
        String sku = "MACBOOK-PRO";
        stockMovementRepo.deleteAllBySku(sku);
        stockMovementRepo.tryInsertMovement(UUID.randomUUID(), sku, 10); // Estoque inicial = 10

        UUID saleMovementId = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger appliedMovements = new AtomicInteger(0);

        var futures = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (stockMovementRepo.tryInsertMovement(saleMovementId, sku, -2) == 1) {
                        appliedMovements.incrementAndGet();
                    }
                }, pool))
                .toList();

        gate.countDown();
        futures.forEach(CompletableFuture::join);
        pool.shutdown();

        assertThat(appliedMovements.get()).isEqualTo(1);
        assertThat(stockMovementRepo.getCurrentStock(sku)).isEqualTo(8);

        // Parte 2: Chave presa em IN_PROGRESS (Lease de 50ms expira apos crash simulado)
        String stuckKey = "stuck-" + UUID.randomUUID();
        TransferRequest payload = new TransferRequest(1L, 2L, new BigDecimal("500.00"));
        httpIdempotencyService.simulateServerCrashLeavingStuckKey(stuckKey, payload, Duration.ofMillis(100));

        // Enquanto o lease esta ativo -> retorna 409 Conflict
        ResponseEntity<String> conflictWhileLocked = httpIdempotencyService.execute(stuckKey, payload, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "nao-deve-executar-ainda"))
        );
        assertThat(conflictWhileLocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Apos o lease expirar -> assume a chave orfa e conclui com 201 Created!
        Thread.sleep(150);
        ResponseEntity<String> recoveredResponse = httpIdempotencyService.execute(stuckKey, payload, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "RECUPERADO_COM_SUCESSO"))
        );
        assertThat(recoveredResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recoveredResponse.getBody()).contains("RECUPERADO_COM_SUCESSO");
    }
}
