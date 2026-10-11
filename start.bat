@echo off
REM ==============================================================================
REM Script de Inicio Rápido para Windows - Tienda Virtual Multicanal, Semiautogestionada
REM ==============================================================================

echo ===============================================================
echo    Tienda Virtual Multicanal, Semiautogestionada (Código Privado)
echo ===============================================================

REM La app compila y corre con Java 21 (misma version que Docker y CI).
java -version >nul 2>&1
IF ERRORLEVEL 1 (
    echo [!] No se encontro Java. Instala JDK 21 o superior ^(por ejemplo Temurin^) y vuelve a intentar.
    pause
    exit /b 1
)

IF NOT EXIST local.properties (
    echo [+] Creando local.properties desde local.properties.example...
    copy local.properties.example local.properties
)

echo [+] Servidor iniciado. Abre tu navegador en:
echo     👉 http://localhost:8080
echo ===============================================================

gradlew.bat run
pause
