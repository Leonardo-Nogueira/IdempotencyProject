package com.leonardonogueira.idempotency.api;

import com.leonardonogueira.idempotency.consumers.Approach1NaturalConsumer;
import com.leonardonogueira.idempotency.consumers.Approach3VersionedConsumer;
import com.leonardonogueira.idempotency.contracts.CustomerUpdated;
import com.leonardonogueira.idempotency.domain.CustomerProjectionEntity;
import com.leonardonogueira.idempotency.domain.CustomerProjectionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CENARIO 3: Abordagem 3 - Versionamento Monotonico (Mensagens Fora de Ordem +
 * Duplicadas)
 * Script k6 correspondente: k6/03-customer-out-of-order-versioning.js
 */
@RestController
@RequestMapping("/api")
public class Scenario3CustomerVersioningController {

    private final Approach1NaturalConsumer approach1Consumer;
    private final Approach3VersionedConsumer approach3Consumer;
    private final CustomerProjectionRepository customerRepo;

    public Scenario3CustomerVersioningController(Approach1NaturalConsumer approach1Consumer,
            Approach3VersionedConsumer approach3Consumer,
            CustomerProjectionRepository customerRepo) {
        this.approach1Consumer = approach1Consumer;
        this.approach3Consumer = approach3Consumer;
        this.customerRepo = customerRepo;
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<CustomerProjectionEntity> getCustomer(@PathVariable Long id) {
        return customerRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Upsert sem verificar se a versao recebida e mais antiga que a atual no banco.
     * Se v2 chegar depois de v3, regride os dados do cliente para a v2!
     */
    @PostMapping("/wrong/customers/upsert")
    public Map<String, Object> wrongCustomerUpsert(@RequestBody CustomerUpdated event) {
        approach1Consumer.handleCustomerUpsert(event);
        return Map.of("applied", true, "version", event.version());
    }

    /**
     * UPDATE CustomerProjectionEntity c SET ... WHERE c.id = :id AND c.version <
     * :version.
     * Descarta tanto duplicatas (v3 == v3) quanto mensagens fora de ordem (v2 <
     * v3)!
     */
    @PostMapping("/fixed/customers/versioned")
    public Map<String, Object> fixedCustomerVersioned(@RequestBody CustomerUpdated event) {
        boolean applied = approach3Consumer.handleCustomerUpdated(event);
        return Map.of("applied", applied, "version", event.version());
    }
}
