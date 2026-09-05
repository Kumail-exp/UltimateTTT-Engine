@echo off
title Ultimate Tic-Tac-Toe
cd /d "%~dp0"

if not exist "ultimattt-1.0.0.jar" (
    if exist "target\ultimattt-1.0.0.jar" (
        set JAR=target\ultimattt-1.0.0.jar
    ) else (
        echo JAR not found. Please place ultimattt-1.0.0.jar next to this .bat
        pause
        exit /b 1
    )
) else (
    set JAR=ultimattt-1.0.0.jar
)

java -jar "%JAR%" play %*
