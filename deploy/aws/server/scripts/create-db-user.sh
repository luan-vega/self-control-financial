#!/usr/bin/env bash
# Cria no RDS o banco "selfcontrol" e o usuário da app (sem superusuário, sem BYPASSRLS, para o
# Row-Level Security valer) e grava DB_URL, DB_USER e DB_PASSWORD no .env. Rode uma vez:
#
#   sudo /opt/self-control/scripts/create-db-user.sh <endpoint-do-rds>
#
# Pede a senha do usuário postgres (terraform output -raw db_master_password).
# A senha da app é gerada aqui e só fica no .env do servidor.
set -euo pipefail
. "$(dirname "$0")/common.sh"
require_root

host=${1:?uso: create-db-user.sh <endpoint-do-rds> [banco] [usuario]}
db=${2:-selfcontrol}
user=${3:-selfcontrol}

[[ "$db" =~ ^[a-z_][a-z0-9_]*$ && "$user" =~ ^[a-z_][a-z0-9_]*$ ]] || die "nome de banco/usuário inválido"

if [ -z "${PGPASSWORD:-}" ]; then
  read -rsp "Senha do usuário postgres do RDS: " PGPASSWORD; echo
fi
export PGPASSWORD PGHOST=$host PGUSER=postgres PGDATABASE=postgres PGSSLMODE=require PGCONNECT_TIMEOUT=10

psql -qAtc 'SELECT 1' >/dev/null || die "não conectou no RDS ($host). Confira o endpoint e o security group."

app_password=$(env_get DB_PASSWORD)
if [ -z "$app_password" ]; then
  app_password=$(openssl rand -base64 36 | tr -dc 'A-Za-z0-9' | head -c 40)
fi

if [ "$(psql -qAtc "SELECT 1 FROM pg_roles WHERE rolname = '$user'")" = "1" ]; then
  log "usuário $user já existe: atualizando a senha"
  psql -v ON_ERROR_STOP=1 -q -v pw="$app_password" <<SQL
ALTER ROLE $user WITH LOGIN PASSWORD :'pw' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
SQL
else
  psql -v ON_ERROR_STOP=1 -q -v pw="$app_password" <<SQL
CREATE ROLE $user WITH LOGIN PASSWORD :'pw' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
SQL
fi

if [ "$(psql -qAtc "SELECT 1 FROM pg_database WHERE datname = '$db'")" != "1" ]; then
  # No RDS o postgres não é superusuário de verdade: para criar um banco com outro dono,
  # ele precisa poder assumir esse papel (PostgreSQL 16+)
  psql -v ON_ERROR_STOP=1 -q <<SQL
GRANT $user TO CURRENT_USER;
CREATE DATABASE $db OWNER $user;
SQL
fi

env_set DB_URL "jdbc:postgresql://$host:5432/$db?sslmode=require"
env_set DB_USER "$user"
env_set DB_PASSWORD "$app_password"

# Confere que o usuário da app entra e não ignora o RLS (a app se recusa a subir nesse caso)
bypass=$(PGUSER=$user PGPASSWORD=$app_password PGDATABASE=$db psql -qAtc \
  "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
[ "$bypass" = "f" ] || die "o usuário $user ignora RLS"

log "banco $db e usuário $user prontos; DB_URL, DB_USER e DB_PASSWORD gravados em $ENV_FILE"
