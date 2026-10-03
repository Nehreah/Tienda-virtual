#!/usr/bin/env bash
# ==============================================================================
# Script de Inicio Rápido - Tienda Virtual Multicanal, Semiautogestionada
# ==============================================================================

set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RESET='\033[0m'

echo -e "${CYAN}===============================================================${RESET}"
echo -e "${CYAN}   🚀 Tienda Virtual Multicanal, Semiautogestionada (Código Privado)${RESET}"
echo -e "${CYAN}===============================================================${RESET}"

# Genera un secreto aleatorio de 32 caracteres alfanuméricos (openssl si existe; si no, /dev/urandom).
gen_secret() {
    local value
    if command -v openssl >/dev/null 2>&1; then
        value="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
    else
        value="$(head -c 256 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
    fi
    if [ "${#value}" -lt 32 ]; then
        echo "No se pudo generar un secreto aleatorio. Instala openssl y vuelve a intentar." >&2
        exit 1
    fi
    printf '%s' "$value"
}

if [ ! -f "local.properties" ]; then
    # Ya NO se copia local.properties.example (traía valores de ejemplo conocidos por cualquiera).
    # Se genera un archivo nuevo con claves aleatorias; las integraciones quedan vacías hasta que las completes.
    echo -e "${YELLOW}[+] Creando 'local.properties' con claves aleatorias...${RESET}"
    DASH_PASS="$(gen_secret)"
    VERIFY_TOKEN="$(gen_secret)"
    (
        umask 077   # solo tu usuario puede leer el archivo
        cat > local.properties <<PROPS
# Generado por start.sh el $(date +%F). NO lo subas a git (ya esta en .gitignore).
# Plantilla con todas las variables y su explicacion: local.properties.example
APP_ENV=local
# DASHBOARD_USER=admin   (por defecto es admin; descomenta para cambiarlo)
DASHBOARD_PASSWORD=${DASH_PASS}
META_WEBHOOK_VERIFY_TOKEN=${VERIFY_TOKEN}

# Integraciones: descomenta y completa SOLO las que uses.
# Mientras esten vacias el tablero las muestra como "sin configurar".
# DATABASE_URL=
# DATABASE_USER=
# DATABASE_PASSWORD=
# SHOPIFY_SHOP_DOMAIN=
# SHOPIFY_ACCESS_TOKEN=
# SHOPIFY_WEBHOOK_SECRET=
# META_CATALOG_ID=
# META_ACCESS_TOKEN=
# META_APP_SECRET=
# WHATSAPP_PHONE_NUMBER_ID=
# WHATSAPP_ACCESS_TOKEN=
# META_PAGE_ID=
# GEMINI_API_KEY=
# PAYMENT_INSTRUCTIONS=
PROPS
    )
    echo -e "${GREEN}[+] Usuario del tablero: admin. La clave quedo en local.properties (DASHBOARD_PASSWORD).${RESET}"
    echo -e "${YELLOW}[!] Antes de publicar en un servidor usa APP_ENV=production. Ver docs/MANUAL_CLAVE_TABLERO.md${RESET}"
fi

PORT=$(grep "^PORT=" local.properties | cut -d'=' -f2 || echo "8080")
PORT=${PORT:-8080}

echo -e "${GREEN}[+] Configuración verificada.${RESET}"
echo -e "${GREEN}[+] Abriendo puerto ${PORT}...${RESET}"
echo -e "${CYAN}---------------------------------------------------------------${RESET}"
echo -e "${CYAN}  El Dashboard Operacional estará disponible en:${RESET}"
echo -e "${YELLOW}  👉 http://127.0.0.1:${PORT}   (en modo local solo se puede abrir desde esta maquina)${RESET}"
echo -e "${CYAN}---------------------------------------------------------------${RESET}"

GRADLE_BIN="./gradlew"
if [ ! -f "$GRADLE_BIN" ] && [ -f "/home/nehreah/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle" ]; then
    GRADLE_BIN="/home/nehreah/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle"
fi

echo -e "${GREEN}[+] Iniciando servidor Ktor Netty...${RESET}"
exec $GRADLE_BIN :app:run
