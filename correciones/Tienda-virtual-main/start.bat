@echo off
REM ==============================================================================
REM Script de Inicio Rápido para Windows - Tienda Virtual Multicanal, Semiautogestionada
REM ==============================================================================

echo ===============================================================
echo    Tienda Virtual Multicanal, Semiautogestionada (Código Privado)
echo ===============================================================

IF NOT EXIST local.properties (
    echo [+] Creando local.properties desde local.properties.example...
    copy local.properties.example local.properties
)

echo [+] Servidor iniciado. Abre tu navegador en:
echo     👉 http://localhost:8080
echo ===============================================================

gradlew.bat run
pause
