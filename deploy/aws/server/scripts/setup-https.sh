#!/usr/bin/env bash
# Pede o certificado do Let's Encrypt para o domínio do APP_PUBLIC_URL e liga o nginx com HTTPS.
# Antes: o registro A do domínio precisa apontar para o IP elástico (confira com: dig +short <domínio>).
#
#   sudo /opt/self-control/scripts/setup-https.sh voce@exemplo.com
#
# A renovação é automática (timer do certbot, 2x por dia) e recarrega o nginx.
set -euo pipefail
. "$(dirname "$0")/common.sh"
require_root

email=${1:?"uso: setup-https.sh <seu-email> (recebe os avisos do Let's Encrypt)"}
domain=$(app_domain)
[ -n "$domain" ] || die "defina APP_PUBLIC_URL=https://<domínio> em $ENV_FILE"

my_ip=$(curl -fsS --max-time 5 https://checkip.amazonaws.com || true)
dns_ip=$(getent ahostsv4 "$domain" | awk 'NR==1{print $1}' || true)
if [ -n "$my_ip" ] && [ "$my_ip" != "$dns_ip" ]; then
  die "$domain resolve para '${dns_ip:-nada}', mas este servidor é $my_ip. Ajuste o DNS e espere propagar."
fi

if [ ! -f "/etc/letsencrypt/live/$domain/fullchain.pem" ]; then
  # nginx provisório só com o desafio ACME
  cp "$APP_DIR/nginx/self-control-http.conf" /etc/nginx/sites-available/self-control-http
  ln -sf /etc/nginx/sites-available/self-control-http /etc/nginx/sites-enabled/self-control-http
  rm -f /etc/nginx/sites-enabled/default /etc/nginx/sites-enabled/self-control
  nginx -t && systemctl reload nginx

  certbot certonly --webroot -w /var/www/certbot -d "$domain" \
    --email "$email" --agree-tos --no-eff-email --non-interactive
fi

# Recarrega o nginx a cada renovação
install -d /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'HOOK'
#!/bin/sh
systemctl reload nginx
HOOK
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

install_nginx_site "$domain"
certbot renew --dry-run --quiet && log "renovação automática testada"
log "HTTPS ativo em https://$domain"
