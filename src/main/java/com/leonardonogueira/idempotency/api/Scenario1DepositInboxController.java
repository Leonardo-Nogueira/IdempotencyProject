package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.consumers.Approach2InboxConsumer;
import com.leonardonogueira.idempotency.consumers.BrokenDepositConsumer;
import com.leonardonogueira.idempotency.contracts.DepositMade;
import com.leonardonogueira.idempotency.domain.BankAccountEntity;
import com.leonardonogueira.idempotency.domain.BankAccountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * CENARIO 1: Operacao Cumulativa (Deposito em Conta: Saldo + Valor)
 * Script k6 correspondente: k6/01-deposit-race-vs-inbox.js
 *
 * Compara:
 * - ❌ /api/wrong/deposits/naive (Sem idempotencia: multiplica o saldo)
 * - ❌ /api/wrong/deposits/race-condition (Check-Then-Act: if !exists -> add ->
 * save, sofre Race Condition)
 * - ✅ /api/fixed/deposits/inbox (Abordagem 2: Tabela Inbox com INSERT ... ON
 * CONFLICT DO NOTHING + @Transactional)
 */
@RestController
@RequestMapping("/api")
public class Scenario1DepositInboxController {

    private final BrokenDepositConsumer brokenConsumer;
    private final Approach2InboxConsumer approach2Consumer;
    private final BankAccountRepository accountRepo;

    public Scenario1DepositInboxController(BrokenDepositConsumer brokenConsumer,
            Approach2InboxConsumer approach2Consumer,
            BankAccountRepository accountRepo) {
        this.brokenConsumer = brokenConsumer;
        this.approach2Consumer = approach2Consumer;
        this.accountRepo = accountRepo;
    }

    public record CreateAccountRequest(Long id, String owner) {
    }

    @PostMapping("/accounts")
    public BankAccountEntity createOrResetAccount(@RequestBody CreateAccountRequest req) {
        return accountRepo.saveAndFlush(new BankAccountEntity(req.id(), req.owner(), BigDecimal.ZERO));
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<BankAccountEntity> getAccount(@PathVariable Long id) {
        return accountRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Soma direto no saldo sem deduplicar.
     */
    @PostMapping("/wrong/deposits/naive")
    public Map<String, Object> wrongDepositNaive(@RequestBody DepositMade event) {
        brokenConsumer.handleNaive(event);
        return Map.of("applied", true, "messageId", event.messageId());
    }

    /**
     * Sofre Race Condition quando varias requisicoes chegam juntas pelo k6!
     */
    @PostMapping("/wrong/deposits/race-condition")
    public Map<String, Object> wrongDepositRaceCondition(@RequestBody DepositMade event) {
        boolean applied = brokenConsumer.handleCheckThenAct(event);
        return Map.of("applied", applied, "messageId", event.messageId());
    }

    /**
     * Reserva atomica na tabela processed_message (ON CONFLICT DO NOTHING) na
     * mesma @Transactional do saldo.
     */
    @PostMapping("/fixed/deposits/inbox")
    public Map<String, Object> fixedDepositInbox(@RequestBody DepositMade event) {
        boolean applied = approach2Consumer.handleDeposit(event);
        return Map.of("applied", applied, "messageId", event.messageId());
    }
}
