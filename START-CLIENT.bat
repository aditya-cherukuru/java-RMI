@echo off
REM ================================================================
REM  RMI ARENA — CLIENT LAUNCHER (for teammates)
REM  Put this file next to rmi-arena.jar and double-click it.
REM  Just type the server IP and your name when asked.
REM ================================================================
cd /d "%~dp0"

echo.
echo =====================================================
echo   RMI ARENA — Client Launcher
echo =====================================================
echo.

REM Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found! Install Java 17+ from https://adoptium.net
    echo        Then close this window and double-click again.
    pause
    exit /b 1
)

REM Check jar exists
if not exist rmi-arena.jar (
    echo [ERROR] rmi-arena.jar not found!
    echo        Copy rmi-arena.jar from Aditya's USB/Discord into this folder.
    pause
    exit /b 1
)

REM Ask for server IP
set /p SERVER_IP="Enter server IP (shown in Aditya's terminal, e.g. 192.168.1.42 or 100.x.x.x): "
if "%SERVER_IP%"=="" (
    echo [ERROR] No IP entered.
    pause
    exit /b 1
)

REM Ask for player name
set /p PLAYER_NAME="Enter your name (no spaces): "
if "%PLAYER_NAME%"=="" set PLAYER_NAME=Player

echo.
echo [INFO] Connecting to %SERVER_IP% as "%PLAYER_NAME%"...
echo [INFO] A game window will open. Press F3 to see RMI stats. TAB to browse server files.
echo.

java -jar rmi-arena.jar client %SERVER_IP% %PLAYER_NAME%

pause
