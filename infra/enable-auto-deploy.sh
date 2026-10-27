#!/usr/bin/env bash
# One-time (and safe to re-run): put the updater on the VM so pushes to the `release` branch deploy
# themselves. Usage: infra/enable-auto-deploy.sh ubuntu@<vm-ip-or-domain>   (or the `gaali` ssh alias)
set -euo pipefail
host=${1:?usage: infra/enable-auto-deploy.sh user@host}
cd "$(dirname "$0")/auto-deploy"

scp gaali-update gaali-update.service gaali-update.timer "$host:/tmp/"
ssh "$host" 'sudo install -m 755 -o root -g root /tmp/gaali-update /usr/local/bin/gaali-update \
  && sudo install -m 644 /tmp/gaali-update.service /tmp/gaali-update.timer /etc/systemd/system/ \
  && rm -f /tmp/gaali-update /tmp/gaali-update.service /tmp/gaali-update.timer \
  && sudo systemctl daemon-reload && sudo systemctl enable --now gaali-update.timer \
  && systemctl list-timers gaali-update.timer --no-pager'
