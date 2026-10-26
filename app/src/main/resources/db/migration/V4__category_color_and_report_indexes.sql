-- Fase 1: cor das categorias (gráficos) e índices para extrato e relatórios.

ALTER TABLE category
    ADD COLUMN color text NOT NULL DEFAULT '#64748b'
        CHECK (color ~ '^#[0-9a-f]{6}$');

ALTER TABLE category
    ADD CONSTRAINT category_name_length CHECK (char_length(name) BETWEEN 1 AND 60);

ALTER TABLE financial_transaction
    ADD CONSTRAINT financial_transaction_description_length CHECK (char_length(description) <= 200);

-- Relatórios filtram por usuário + moeda + período e agrupam por categoria.
CREATE INDEX financial_transaction_user_currency_date_idx
    ON financial_transaction (user_id, currency, occurred_on);

-- O user_id é preenchido pelo contexto do RLS quando o insert não informa.
ALTER TABLE category ALTER COLUMN user_id SET DEFAULT app_current_user_id();
ALTER TABLE financial_transaction ALTER COLUMN user_id SET DEFAULT app_current_user_id();

-- Checagens de chave estrangeira ignoram RLS. Com a FK composta, um lançamento nunca aponta
-- para a categoria de outro usuário, mesmo que alguém adivinhe o id.
ALTER TABLE category ADD CONSTRAINT category_id_user_uk UNIQUE (id, user_id);
ALTER TABLE financial_transaction
    DROP CONSTRAINT financial_transaction_category_id_fkey,
    ADD CONSTRAINT financial_transaction_category_owner_fk
        FOREIGN KEY (category_id, user_id) REFERENCES category (id, user_id)
        ON DELETE SET NULL (category_id);
