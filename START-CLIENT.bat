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
    echo [!] Java 17+ was not found on this computer.
    echo.
    echo [*] Would you like to automatically install Java 17 now using Windows winget?
    set /p AUTO_INSTALL="Install Java now? (Y/N, default Y): "
    if /i not "%AUTO_INSTALL%"=="n" (
        echo.
        echo [*] Installing Java 17 (Eclipse Temurin JRE)... please wait a moment...
        winget install --id EclipseAdoptium.Temurin.17.JRE -e --accept-source-agreements --accept-package-agreements
        echo.
        echo [OK] Installation finished! Please close this window and double-click START-CLIENT.bat again.
        pause
        exit /b 0
    )
    echo [ERROR] Java 17+ is required. Install from https://adoptium.net and try again.
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
