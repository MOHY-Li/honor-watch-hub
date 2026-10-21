#!/usr/bin/env bash
# 一键启动服务端（Linux/macOS）
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data
if [ -f deploy/sources.yaml ]; then
  exec python3 -m hub.main --port "${WATCHHUB_PORT:-8787}" --data data --config deploy/sources.yaml
else
  exec python3 -m hub.main --port "${WATCHHUB_PORT:-8787}" --data data
fi
