#!/usr/bin/env bash
# Test, cross-compile the API for the VM (ARM or x86, detected over ssh), upload the one binary, restart, check health.
# Usage: infra/deploy.sh ubuntu@<vm-ip-or-domain>
set -euo pipefail
host=${1:?usage: infra/deploy.sh user@host}
cd "$(dirname "$0")/../server"

go test ./...
case $(ssh "$host" uname -m) in
  aarch64) arch=arm64 ;;
  x86_64)  arch=amd64 ;;
  *) echo "unknown VM architecture" >&2; exit 1 ;;
esac
out=$(mktemp)
GOOS=linux GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$out" .
scp "$out" "$host:/tmp/gaali-server"
rm -f "$out"
ssh "$host" 'sudo install -m 755 -o root -g root /tmp/gaali-server /opt/gaali/server \
  && sudo systemctl restart gaali-api && sleep 2 \
  && systemctl is-active gaali-api && curl -fsS http://127.0.0.1:8080/healthz && echo'
