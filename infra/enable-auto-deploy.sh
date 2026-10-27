#!/usr/bin/env bash
# One-time (and safe to re-run): put the updater on the VM so pushes to the `release` branch deploy
# themselves. Usage: infra/enable-auto-deploy.sh ubuntu@<vm-ip-or-domain>   (or the `gaali` ssh alias)
set -euo pipefail
host=${1:?usage: infra/enable-auto-deploy.sh user@host}
cd "$(dirname "$0")/auto-deploy"

# The GitHub Actions deploy key may run the updater and nothing else.
printf '%s\n' "command=\"sudo /usr/local/bin/gaali-update\",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding $(cat github-deploy.pub)" \
  | ssh "$host" 'read -r line; mkdir -p ~/.ssh; touch ~/.ssh/authorized_keys
    grep -qF "$(echo "$line" | awk "{print \$(NF-1)}")" ~/.ssh/authorized_keys || echo "$line" >> ~/.ssh/authorized_keys'

scp gaali-update gaali-update.service gaali-update.timer "$host:/tmp/"
ssh "$host" 'sudo install -m 755 -o root -g root /tmp/gaali-update /usr/local/bin/gaali-update \
  && sudo install -m 644 /tmp/gaali-update.service /tmp/gaali-update.timer /etc/systemd/system/ \
  && rm -f /tmp/gaali-update /tmp/gaali-update.service /tmp/gaali-update.timer \
  && sudo systemctl daemon-reload && sudo systemctl enable --now gaali-update.timer \
  && systemctl list-timers gaali-update.timer --no-pager'
