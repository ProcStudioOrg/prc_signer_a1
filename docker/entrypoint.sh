#!/bin/bash
set -e

echo "=========================================="
echo "   ProcStudio Signer - Starting"
echo "=========================================="
echo ""
echo "Backend API: http://localhost:8080"
echo "Frontend Web: http://localhost:80"
echo ""

# Criar diretórios de log se não existirem
mkdir -p /var/log/supervisor
mkdir -p /var/log/nginx

# Notificação de deploy/boot via webhook (best-effort, nunca bloqueia o start).
# Um POST por start de container; ausência do evento indica deploy com falha.
if [ -n "${USAGE_WEBHOOK_URL:-}" ]; then
    APP_VERSION="$(tr -d '[:space:]' < /app/VERSION 2>/dev/null || true)"
    APP_VERSION="${APP_VERSION:-unknown}"
    (
        curl -fsS -m 10 -X POST \
            -H 'Content-Type: application/json' \
            -d "{\"service\":\"signer\",\"event\":\"deploy\",\"version\":\"${APP_VERSION}\"}" \
            "$USAGE_WEBHOOK_URL" > /dev/null 2>&1 || true
    ) &
fi

# Iniciar Supervisor (instalado via pip, binário em /usr/local/bin)
exec /usr/local/bin/supervisord -c /etc/supervisord.conf
