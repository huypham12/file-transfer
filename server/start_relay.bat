@echo off
title TeamShare - Zero-Knowledge Relay Server
echo ========================================================
echo   TeamShare - May chu chuyen tiep Zero-Knowledge Relay
echo   Cam ket bao mat: Chuyen tiep RAM, khong ghi dia
echo ========================================================
echo.
cd /d "%~dp0"
if exist relay.exe (
    echo Dang khoi dong relay.exe tren cong 8080...
    relay.exe -addr :8080
) else (
    echo Dang khoi dong qua Go tren cong 8080...
    go run cmd/relay/main.go -addr :8080
)
pause
