-- Fase 3: cadastro aberto, perfil, papéis, planos/cobrança, chaves de API e trilha de auditoria.
--
-- As tabelas novas que não têm RLS (app_user, user_token, api_key, subscription, audit_event,
-- billing_event) são lidas antes de existir um usuário no contexto: login, link do e-mail, chave
-- de API, webhook do Stripe. As telas sempre filtram por user_id = app_current_user_id().

-- Perfil, papel e situação da conta
ALTER TABLE app_user
    ADD COLUMN role              text        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    ADD COLUMN active            boolean     NOT NULL DEFAULT true,
    ADD COLUMN email_verified_at timestamptz,
    ADD COLUMN terms_accepted_at timestamptz,
    ADD COLUMN terms_version     text,
    ADD COLUMN avatar_url        text CHECK (avatar_url IS NULL OR avatar_url ~ '^https://'),
    ADD COLUMN last_login_at     timestamptz,
    ADD COLUMN updated_at        timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT app_user_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 80);

-- Contas criadas antes desta fase: o e-mail veio do Google (verificado) ou foi criado à mão.
UPDATE app_user SET email_verified_at = created_at WHERE email_verified_at IS NULL;

-- Foto enviada pelo usuário. Separada para não carregar bytes em toda leitura de app_user.
CREATE TABLE user_avatar (
    user_id      uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    content_type text        NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    bytes        bytea       NOT NULL CHECK (octet_length(bytes) <= 524288),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Tokens de uso único enviados por e-mail (confirmação e troca de senha). Só o SHA-256 fica no banco.
CREATE TABLE user_token (
    token_hash text PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX user_token_user_idx ON user_token (user_id, purpose);

-- Chaves da API pública (/api/v1). Várias por usuário; só o SHA-256 fica no banco.
CREATE TABLE api_key (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name         text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    key_hash     text        NOT NULL UNIQUE,
    key_hint     text        NOT NULL,
    read_only    boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX api_key_user_idx ON api_key (user_id, created_at DESC);

-- Plano de cada usuário. Sem linha = FREE.
CREATE TABLE subscription (
    user_id                uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    plan                   text        NOT NULL CHECK (plan IN ('FREE', 'PRO')),
    status                 text        NOT NULL CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELED')),
    provider               text        NOT NULL CHECK (provider IN ('MOCK', 'STRIPE')),
    customer_id            text UNIQUE,
    external_id            text UNIQUE,            -- id da assinatura no Stripe
    current_period_end     timestamptz,
    cancel_at_period_end   boolean     NOT NULL DEFAULT false,
    updated_at             timestamptz NOT NULL DEFAULT now()
);

-- Eventos de cobrança já processados: o Stripe reenvia webhooks, e cada um só pode valer uma vez.
CREATE TABLE billing_event (
    id          text PRIMARY KEY,
    type        text        NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

-- Trilha de auditoria: quem fez o quê, quando e de onde. Só recebe inserts.
CREATE TABLE audit_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id    uuid REFERENCES app_user (id) ON DELETE SET NULL,   -- quem fez (nulo: anônimo ou sistema)
    subject_id  uuid REFERENCES app_user (id) ON DELETE SET NULL,   -- conta afetada
    action      text        NOT NULL,
    target_type text,
    target_id   text,
    channel     text        NOT NULL CHECK (channel IN ('WEB', 'API', 'WEBHOOK', 'SYSTEM')),
    ip          text,
    user_agent  text,
    details     jsonb       NOT NULL DEFAULT '{}'
);

CREATE INDEX audit_event_subject_idx ON audit_event (subject_id, occurred_at DESC);
CREATE INDEX audit_event_occurred_idx ON audit_event (occurred_at DESC);

CREATE FUNCTION audit_event_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- O ON DELETE SET NULL das chaves estrangeiras é a única alteração permitida (exclusão de conta).
    IF TG_OP = 'UPDATE' AND NEW.id = OLD.id AND NEW.action = OLD.action AND NEW.occurred_at = OLD.occurred_at
       AND NEW.details = OLD.details
       AND (NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id OR NEW.actor_id IS NULL)
       AND (NEW.subject_id IS NOT DISTINCT FROM OLD.subject_id OR NEW.subject_id IS NULL) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'audit_event é somente inserção';
END
$$;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

-- Limite do plano Free: lançamentos criados no mês, por usuário.
CREATE INDEX financial_transaction_user_created_idx ON financial_transaction (user_id, created_at);

-- Relatórios: o índice cobre todas as colunas lidas, então o Postgres responde só com o índice
-- (index-only scan) em vez de visitar a tabela. Substitui o índice da Fase 1 com as mesmas chaves.
CREATE INDEX financial_transaction_report_idx
    ON financial_transaction (user_id, currency, occurred_on) INCLUDE (type, amount_cents, category_id);
DROP INDEX financial_transaction_user_currency_date_idx;

-- Pendentes criados pela API pública (POST /api/v1/inbox).
ALTER TABLE inbox_item DROP CONSTRAINT inbox_item_source_check;
ALTER TABLE inbox_item ADD CONSTRAINT inbox_item_source_check CHECK (source IN ('OFX', 'CSV', 'WEBHOOK', 'API'));

-- Contagem de pendentes (selo da caixa de entrada) sem ler os descartados.
CREATE INDEX inbox_item_pending_idx ON inbox_item (user_id) WHERE status = 'PENDING';
