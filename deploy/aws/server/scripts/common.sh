# shellcheck shell=bash disable=SC2034
# Funções compartilhadas pelos scripts (source, não executar)
APP_DIR=/opt/self-control
ENV_FILE=$APP_DIR/.env
LOG_DIR=/var/log/self-control

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERRO: $*" >&2; exit 1; }

require_root() { [ "$(id -u)" -eq 0 ] || die "rode com sudo"; }

# Lê uma variável do .env (sem executar o arquivo)
env_get() { { grep -E "^$1=" "$ENV_FILE" 2>/dev/null || true; } | tail -n1 | cut -d= -f2-; }

# Grava/atualiza uma variável no .env
env_set() {
  local key=$1 value=$2
  touch "$ENV_FILE" && chmod 600 "$ENV_FILE"
  if grep -qE "^$key=" "$ENV_FILE"; then
    local tmp; tmp=$(mktemp)
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' "$ENV_FILE" > "$tmp"
    cat "$tmp" > "$ENV_FILE" && rm -f "$tmp"
  else
    echo "$key=$value" >> "$ENV_FILE"
  fi
}

# Domínio a partir do APP_PUBLIC_URL (https://dominio/...)
app_domain() { env_get APP_PUBLIC_URL | sed -E 's#^[a-z]+://##; s#[:/].*$##'; }

# Instala a configuração do nginx com o domínio (só se o certificado já existe)
install_nginx_site() {
  local domain=$1
  sed "s/__DOMAIN__/$domain/g" "$APP_DIR/nginx/self-control.conf" > /etc/nginx/sites-available/self-control
  ln -sf /etc/nginx/sites-available/self-control /etc/nginx/sites-enabled/self-control
  rm -f /etc/nginx/sites-enabled/default /etc/nginx/sites-enabled/self-control-http
  nginx -t && systemctl reload nginx
}
