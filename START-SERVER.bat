@echo off
REM ================================================================
REM  RMI ARENA — SERVER LAUNCHER
REM  Double-click this on Aditya's laptop to start the server.
REM  Automatically picks the best IP (Tailscale > LAN > localhost).
REM ================================================================
cd /d "%~dp0"

echo.
echo =====================================================
echo   RMI ARENA — Server Launcher
echo =====================================================
echo.

REM Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found! Install Java 17+ from https://adoptium.net
    pause
    exit /b 1
)

REM Check jar exists
if not exist rmi-arena.jar (
    echo [ERROR] rmi-arena.jar not found. Run build.bat first.
    pause
    exit /b 1
)

REM Auto-detect Tailscale IP (100.x.x.x range)
set TAILSCALE_IP=
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "100\."') do (
    set RAW=%%a
    set RAW=!RAW: =!
)

REM Try to find Tailscale adapter
for /f "tokens=*" %%i in ('ipconfig ^| findstr /i "100\.6[4-9]\|100\.[7-9][0-9]\|100\.1[0-2][0-9]"') do (
    for /f "tokens=3 delims= " %%j in ("%%i") do set TAILSCALE_IP=%%j
)

if defined TAILSCALE_IP (
    echo [INFO] Tailscale detected: %TAILSCALE_IP%
    echo [INFO] Starting server with Tailscale IP...
    echo.
    java -jar rmi-arena.jar server --host %TAILSCALE_IP%
) else (
    echo [INFO] No Tailscale detected — using auto-detected LAN IP.
    echo [INFO] If the wrong IP appears, restart with: java -jar rmi-arena.jar server --host YOUR_IP
    echo.
    java -jar rmi-arena.jar server
)

pause
