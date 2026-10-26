#!/usr/bin/env bash
# Troca a versão da app e volta para a anterior se a nova não ficar saudável.
#
#   sudo /opt/self-control/scripts/deploy.sh <tag>     # sha completo do commit, versão ou "latest"
#
# Chamado pelo GitHub Actions (deploy-aws.yml) via SSM; também serve para deploy e rollback manuais.
# Com uma instância só, a app fica fora por ~30-60 s enquanto a nova sobe (o nginx mostra a página
# de manutenção nesse meio-tempo).
set -euo pipefail
. "$(dirname "$0")/common.sh"
require_root

tag=${1:-latest}
[[ "$tag" =~ ^[A-Za-z0-9._-]+$ ]] || die "tag inválida: $tag"
[ -f "$ENV_FILE" ] || die "falta $ENV_FILE (docs/deploy-aws.md, passo 5)"
[ -n "$(env_get DB_PASSWORD)" ] || die "DB_PASSWORD vazio em $ENV_FILE: rode scripts/create-db-user.sh"

exec > >(tee -a "$LOG_DIR/deploy.log") 2>&1
cd "$APP_DIR"

previous=$(env_get IMAGE_TAG)
log "deploy: ${previous:-nenhuma} -> $tag"

wait_ready() {
  # Readiness inclui o banco; X-Forwarded-Proto porque o perfil prod recusa HTTP puro
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 -H 'X-Forwarded-Proto: https' \
        http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Status}}' self-control-app 2>/dev/null)" = "exited" ]; then
      return 1
    fi
    sleep 5
  done
  return 1
}

env_set IMAGE_TAG "$tag"
if ! docker compose pull --quiet app; then
  env_set IMAGE_TAG "$previous"
  die "não baixou a imagem $tag (existe no GHCR? o servidor fez docker login no ghcr.io?)"
fi
docker compose up -d --remove-orphans app

if wait_ready; then
  log "app saudável na versão $tag"
  docker image prune -f >/dev/null
  exit 0
fi

log "versão $tag não ficou saudável. Últimas linhas do log:"
docker compose logs --tail 80 app || true

if [ -n "$previous" ] && [ "$previous" != "$tag" ]; then
  log "voltando para $previous"
  env_set IMAGE_TAG "$previous"
  docker compose up -d app
  if wait_ready; then
    log "rollback para $previous ok"
  else
    log "rollback para $previous também não ficou saudável"
  fi
fi
die "deploy de $tag falhou"
