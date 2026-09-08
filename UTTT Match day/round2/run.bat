@echo off
setlocal EnableDelayedExpansion
title Ultimate Tic-Tac-Toe - Round 2 (15s/move)
cd /d "%~dp0"

if not exist "ultimattt-1.0.0.jar" (
    echo JAR not found next to this .bat
    pause
    exit /b 1
)

REM Find next free "Game N.txt" (colon is illegal in Windows filenames)
set N=1
:findlog
if exist "Game !N!.txt" (
    set /a N+=1
    goto findlog
)
set LOGFILE=Game !N!.txt

echo.
echo Transcript will be saved to: !LOGFILE!
echo.

java -jar "ultimattt-1.0.0.jar" play --limit 15000 --depth 24 --log "!LOGFILE!" %*

echo.
if exist "!LOGFILE!" (
    echo Game transcript saved as: !LOGFILE!
) else (
    echo Note: no transcript file was created.
)
echo.
