#!/bin/sh
# Cria o banco e um usuário comum (não superusuário, sem BYPASSRLS) dono do schema.
# Superusuários ignoram Row-Level Security, então a aplicação nunca deve usar o usuário postgres.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE ROLE ${APP_DB_USER} LOGIN PASSWORD '${APP_DB_PASSWORD}' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
CREATE DATABASE ${APP_DB_NAME} OWNER ${APP_DB_USER};
SQL
