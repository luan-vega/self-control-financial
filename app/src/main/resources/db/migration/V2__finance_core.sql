-- Núcleo financeiro: categorias e lançamentos, isolados por usuário via Row-Level Security.
-- A aplicação deve executar, dentro de cada transação:
--   SELECT set_config('app.current_user_id', '<uuid>', true);

CREATE FUNCTION app_current_user_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$
    SELECT nullif(current_setting('app.current_user_id', true), '')::uuid
$$;

CREATE TABLE category (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    type       text        NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, type, name)
);

CREATE TABLE financial_transaction (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category_id  uuid REFERENCES category (id) ON DELETE SET NULL,
    type         text        NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),   -- sempre positivo; o sinal vem de type
    currency     char(3)     NOT NULL,
    occurred_on  date        NOT NULL,
    description  text        NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX financial_transaction_user_date_idx ON financial_transaction (user_id, occurred_on DESC);
CREATE INDEX financial_transaction_category_idx ON financial_transaction (category_id);

-- FORCE faz a política valer também para o dono das tabelas (o usuário da aplicação).
ALTER TABLE category ENABLE ROW LEVEL SECURITY;
ALTER TABLE category FORCE ROW LEVEL SECURITY;
CREATE POLICY category_owner ON category
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

ALTER TABLE financial_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_transaction FORCE ROW LEVEL SECURITY;
CREATE POLICY financial_transaction_owner ON financial_transaction
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
