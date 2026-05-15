#!/usr/bin/env bash
# Smoke test del server MCP via stdio.
# Manda: initialize -> initialized -> tools/list -> tools/call pt_get_topology
# y muestra las respuestas.
#
# Requisitos:
#   - JAVA_HOME apuntando a un JDK 21.
#   - PT corriendo, IPC escuchando en 39000, app registrada.
#   - Credenciales en variables de entorno o local.properties.
#   - El proyecto compilado: ./gradlew installDist
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "Setea JAVA_HOME al JDK 21 antes de correr este script." >&2
    exit 1
fi
JAVA="$JAVA_HOME/bin/java"

if [ ! -d "build/install/pt-mcp/lib" ]; then
    echo "Falta build/install/pt-mcp/. Corre: ./gradlew installDist" >&2
    exit 1
fi

REQ_FILE=$(mktemp)
cat > "$REQ_FILE" <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pt_get_topology","arguments":{}}}
EOF

CP="build/install/pt-mcp/lib/*"

# Mantener stdin abierto unos segundos para que el server pueda responder
# antes de que se cierre el pipe.
(cat "$REQ_FILE"; sleep 5) | "$JAVA" -cp "$CP" com.ptmcp.PtMcpServer > smoke-mcp.stdout.log 2> smoke-mcp.stderr.log

rm -f "$REQ_FILE"
echo "=== STDOUT ==="
cat smoke-mcp.stdout.log
echo
echo "=== STDERR (last 20 lines) ==="
tail -20 smoke-mcp.stderr.log
