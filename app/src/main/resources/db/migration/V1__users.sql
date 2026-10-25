-- Usuários e identidades externas (Google). Sem RLS: é lida antes de existir um usuário no contexto.

CREATE TABLE app_user (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email            text        NOT NULL,
    display_name     text        NOT NULL,
    password_hash    text,                      -- null para contas só com Google
    default_currency char(3)     NOT NULL DEFAULT 'BRL',
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_uk ON app_user (lower(email));

CREATE TABLE user_identity (
    provider   text        NOT NULL,            -- ex.: 'google'
    subject    text        NOT NULL,            -- id do usuário no provedor
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);

CREATE INDEX user_identity_user_idx ON user_identity (user_id);
