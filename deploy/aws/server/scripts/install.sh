#!/usr/bin/env bash
# Copia os arquivos do servidor (deste diretório) para /opt/self-control. Nunca mexe no .env.
# Usado no primeiro setup (sudo bash install.sh, depois de copiar a pasta deploy/aws/server)
# e em todo deploy pelo GitHub Actions, para o servidor acompanhar o repositório.
set -euo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
. "$SRC/scripts/common.sh"
require_root

install -d -m 750 "$APP_DIR" "$APP_DIR/scripts" "$APP_DIR/nginx"
install -m 640 "$SRC/docker-compose.yml" "$SRC/env.example" "$APP_DIR/"
install -m 750 "$SRC"/scripts/*.sh "$APP_DIR/scripts/"
install -m 640 "$SRC"/nginx/*.conf "$APP_DIR/nginx/"
install -d -m 755 /var/www/self-control /var/www/certbot
install -m 644 "$SRC/nginx/maintenance.html" /var/www/self-control/
install -m 644 "$SRC/logrotate.conf" /etc/logrotate.d/self-control
install -d -m 755 "$LOG_DIR" "$LOG_DIR/nginx"
install -d -m 755 -o 10001 -g 10001 "$LOG_DIR/app"

# Atualiza o nginx só depois que o HTTPS foi configurado (setup-https.sh)
domain=$(app_domain || true)
if [ -n "$domain" ] && [ -f "/etc/letsencrypt/live/$domain/fullchain.pem" ]; then
  install_nginx_site "$domain"
fi

[ -f "$ENV_FILE" ] || log "Falta $ENV_FILE: copie $APP_DIR/env.example e rode scripts/create-db-user.sh"
log "arquivos instalados em $APP_DIR"
