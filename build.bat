@echo off
rem Builds rmi-arena.jar (needs a JDK 17 or newer)
cd /d "%~dp0"
if exist out rmdir /s /q out
javac --release 17 -d out src\arena\*.java src\arena\common\*.java src\arena\server\*.java src\arena\client\*.java src\arena\bot\*.java
if errorlevel 1 exit /b 1
jar --create --file rmi-arena.jar --main-class arena.Main -C out .
if errorlevel 1 exit /b 1
echo Built rmi-arena.jar
