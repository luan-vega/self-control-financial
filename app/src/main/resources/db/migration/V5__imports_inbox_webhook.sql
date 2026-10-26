-- Fase 2: importação de extratos (OFX/CSV), caixa de entrada e webhook dos Atalhos do iOS.
--
-- Fluxo: o arquivo vira um import_batch em PREVIEW com uma import_row por linha (o log do import).
-- Ao confirmar, as linhas escolhidas entram na inbox_item como PENDING, numa única transação.
-- Na caixa de entrada o usuário revisa e confirma (vira financial_transaction) ou descarta.

CREATE TABLE import_batch (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL DEFAULT app_current_user_id() REFERENCES app_user (id) ON DELETE CASCADE,
    source          text        NOT NULL CHECK (source IN ('OFX', 'CSV')),
    file_name       text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    status          text        NOT NULL DEFAULT 'PREVIEW' CHECK (status IN ('PREVIEW', 'IMPORTED', 'CANCELLED')),
    total_rows      int         NOT NULL DEFAULT 0,
    new_rows        int         NOT NULL DEFAULT 0,
    duplicate_rows  int         NOT NULL DEFAULT 0,
    error_rows      int         NOT NULL DEFAULT 0,
    imported_rows   int         NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    UNIQUE (id, user_id)
);

CREATE INDEX import_batch_user_created_idx ON import_batch (user_id, created_at DESC);

-- Uma linha do arquivo, já interpretada. Também é o log: erro, duplicata ou válida, com a mensagem.
CREATE TABLE import_row (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid    NOT NULL DEFAULT app_current_user_id() REFERENCES app_user (id) ON DELETE CASCADE,
    batch_id     uuid    NOT NULL,
    line_no      int     NOT NULL,
    status       text    NOT NULL CHECK (status IN ('NEW', 'DUPLICATE', 'ERROR')),
    type         text    CHECK (type IN ('INCOME', 'EXPENSE')),
    amount_cents bigint  CHECK (amount_cents > 0),
    currency     char(3),
    occurred_on  date,
    description  text    NOT NULL DEFAULT '' CHECK (char_length(description) <= 200),
    category_id  uuid,
    external_id  text,
    message      text,
    -- Linhas válidas e duplicatas precisam de todos os dados para virar lançamento.
    CHECK (status = 'ERROR' OR (type IS NOT NULL AND amount_cents IS NOT NULL
                                AND currency IS NOT NULL AND occurred_on IS NOT NULL)),
    FOREIGN KEY (batch_id, user_id) REFERENCES import_batch (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id, user_id) REFERENCES category (id, user_id) ON DELETE SET NULL (category_id)
);

CREATE INDEX import_row_batch_idx ON import_row (batch_id, line_no);

-- Caixa de entrada: lançamentos que chegaram por import ou webhook e ainda não foram confirmados.
-- Descartados ficam guardados (DISCARDED) para que reimportar o mesmo extrato não os traga de volta.
CREATE TABLE inbox_item (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL DEFAULT app_current_user_id() REFERENCES app_user (id) ON DELETE CASCADE,
    batch_id     uuid,
    source       text        NOT NULL CHECK (source IN ('OFX', 'CSV', 'WEBHOOK')),
    status       text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DISCARDED')),
    type         text        NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    currency     char(3)     NOT NULL,
    occurred_on  date        NOT NULL,
    description  text        NOT NULL DEFAULT '' CHECK (char_length(description) <= 200),
    category_id  uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (batch_id, user_id) REFERENCES import_batch (id, user_id) ON DELETE SET NULL (batch_id),
    FOREIGN KEY (category_id, user_id) REFERENCES category (id, user_id) ON DELETE SET NULL (category_id)
);

CREATE INDEX inbox_item_user_status_idx ON inbox_item (user_id, status, occurred_on DESC);
CREATE INDEX inbox_item_category_idx ON inbox_item (category_id);

ALTER TABLE import_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY import_batch_owner ON import_batch
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

ALTER TABLE import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_row FORCE ROW LEVEL SECURITY;
CREATE POLICY import_row_owner ON import_row
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

ALTER TABLE inbox_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox_item FORCE ROW LEVEL SECURITY;
CREATE POLICY inbox_item_owner ON inbox_item
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

-- Token do webhook dos Atalhos (um por usuário). Guarda só o SHA-256 do token.
-- Sem RLS, como app_user: é lida para descobrir QUEM está chamando, antes de existir usuário no contexto.
-- As telas sempre filtram por user_id = app_current_user_id().
CREATE TABLE webhook_token (
    user_id      uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    token_hint   text        NOT NULL,          -- últimos caracteres, para reconhecer o token na tela
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
