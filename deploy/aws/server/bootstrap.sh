#!/usr/bin/env bash
# Prepara uma EC2 Ubuntu 24.04 nova para rodar a app. Roda sozinho no primeiro boot (user_data do
# Terraform) ou à mão: sudo bash bootstrap.sh. Pode rodar de novo sem estragar nada.
# Log do primeiro boot: /var/log/cloud-init-output.log
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive

# 2 GB de swap: com 1 GB de RAM, a JVM + nginx + apt sem swap acabam no OOM killer
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
# Usa o swap só como reserva, não para empurrar a JVM para o disco
echo 'vm.swappiness=10' > /etc/sysctl.d/90-self-control.conf
sysctl --system >/dev/null

timedatectl set-timezone America/Sao_Paulo

apt-get update
apt-get -y upgrade
apt-get install -y --no-install-recommends \
  docker.io docker-compose-v2 nginx certbot curl jq unattended-upgrades postgresql-client

# Atualizações de segurança automáticas (sem reboot automático: reinicie numa hora calma)
dpkg-reconfigure -f noninteractive unattended-upgrades

systemctl enable --now docker
usermod -aG docker ubuntu

# Pastas da app. A app roda como uid 10001 dentro do container e escreve o log em /var/log/self-control/app.
install -d -m 750 -o root -g root /opt/self-control
install -d -m 755 /var/log/self-control
install -d -m 755 -o 10001 -g 10001 /var/log/self-control/app
install -d -m 755 /var/log/self-control/nginx
install -d -m 755 /var/www/certbot

# nginx começa com a página padrão; setup-https.sh troca pela configuração da app
systemctl enable --now nginx

echo "bootstrap ok"
