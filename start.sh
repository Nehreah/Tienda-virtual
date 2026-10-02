#!/usr/bin/env bash
# ==============================================================================
# Script de Inicio Rápido - Autómata Comercial Multicanal v5.3
# ==============================================================================

set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RESET='\033[0m'

echo -e "${CYAN}===============================================================${RESET}"
echo -e "${CYAN}   🚀 Autómata Comercial Multicanal v5.3 (Código Privado)${RESET}"
echo -e "${CYAN}===============================================================${RESET}"

if [ ! -f "local.properties" ]; then
    echo -e "${YELLOW}[+] Creando 'local.properties' desde 'local.properties.example'...${RESET}"
    cp local.properties.example local.properties
fi

PORT=$(grep "^PORT=" local.properties | cut -d'=' -f2 || echo "8080")
PORT=${PORT:-8080}

echo -e "${GREEN}[+] Configuración verificada.${RESET}"
echo -e "${GREEN}[+] Abriendo puerto ${PORT}...${RESET}"
echo -e "${CYAN}---------------------------------------------------------------${RESET}"
echo -e "${CYAN}  El Dashboard Operacional estará disponible en:${RESET}"
echo -e "${YELLOW}  👉 http://localhost:${PORT}${RESET}"
echo -e "${CYAN}---------------------------------------------------------------${RESET}"

GRADLE_BIN="./gradlew"
if [ ! -f "$GRADLE_BIN" ] && [ -f "/home/nehreah/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle" ]; then
    GRADLE_BIN="/home/nehreah/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle"
fi

echo -e "${GREEN}[+] Iniciando servidor Ktor Netty...${RESET}"
exec $GRADLE_BIN :app:run
