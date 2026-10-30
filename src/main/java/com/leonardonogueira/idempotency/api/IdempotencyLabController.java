package com.leonardonogueira.idempotency.api;

/**
 * Os endpoints foram separados em 4 Controllers dedicados por cenario para facilitar o estudo:
 *
 * 1. {@link Scenario1DepositInboxController}        -> k6/01-deposit-race-vs-inbox.js
 * 2. {@link Scenario2OrderStateMachineController}   -> k6/02-order-state-machine.js
 * 3. {@link Scenario3CustomerVersioningController}  -> k6/03-customer-out-of-order-versioning.js
 * 4. {@link Scenario4HttpIdempotencyKeyController}  -> k6/04-http-idempotency-key.js
 */
public final class IdempotencyLabController {
    private IdempotencyLabController() {}
}
