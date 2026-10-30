-- ============================================================================
-- SCHEMA DO LABORATORIO DE IDEMPOTENCIA (Compativel com PostgreSQL e H2 Mode)
-- Baseado nas secoes 2.2, 2.4 e 2.7 de Assincrono&Idempotency.md
-- ============================================================================

-- 1. Abordagem 1: Estado / Maquina de Estados (Pedido)
CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT PRIMARY KEY,
    amount      NUMERIC(15, 2) NOT NULL,
    status      VARCHAR(30)    NOT NULL, -- PENDING | PAID | CANCELLED | REFUNDED
    updated_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Abordagem 1 & 3: Projecao de Cliente (Upsert e Versionamento Monotonico)
CREATE TABLE IF NOT EXISTS customer_projection (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    email       VARCHAR(150) NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Abordagem 2: Conta Bancaria (Operacao Cumulativa: Saldo) + Tabela Inbox
CREATE TABLE IF NOT EXISTS bank_account (
    id          BIGINT PRIMARY KEY,
    owner       VARCHAR(120)   NOT NULL,
    balance     NUMERIC(15, 2) NOT NULL DEFAULT 0.00
);

-- Tabela Inbox (processed_message): A autoridade final da deduplicacao e a UNIQUE / PK constraint!
CREATE TABLE IF NOT EXISTS processed_message (
    message_id   UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, consumer)
);

-- 4. Abordagem HTTP: Idempotency-Key com Lease/Timeout (Secoes 2.4 e 2.7)
-- O campo locked_until evita que uma chave fique presa em IN_PROGRESS para sempre se o servidor cair!
CREATE TABLE IF NOT EXISTS idempotency_key (
    idem_key      VARCHAR(100) PRIMARY KEY,
    request_hash  VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,  -- IN_PROGRESS | COMPLETED
    locked_until  TIMESTAMP    NOT NULL,  -- Lease de expiracao para chaves IN_PROGRESS orfas
    response_code INT,
    response_body VARCHAR(4000),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. Cenario 6 (Nivel Arquiteto - Secao 2.7): Ledger Imutavel de Estoque/Saldo
-- Em vez de UPDATE saldo = saldo + delta + tabela processed_message, cada movimento e uma linha
-- imutavel cuja propria PRIMARY KEY (id) ja garante a idempotencia natural!
CREATE TABLE IF NOT EXISTS stock_movement (
    id          UUID PRIMARY KEY,
    sku         VARCHAR(50) NOT NULL,
    delta       INT         NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
