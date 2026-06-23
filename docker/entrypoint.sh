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

# Iniciar Supervisor
exec /usr/bin/supervisord -c /etc/supervisord.conf
