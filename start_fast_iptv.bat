@echo off
title FastIPTV (HMP5000) Launcher
cd /d C:\IPTV

echo Checking for existing FastIPTV instance on port 8899...
powershell -NoProfile -Command "$conn = Get-NetTCPConnection -LocalPort 8899 -State Listen -ErrorAction SilentlyContinue; if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 400 }"

echo Compiling FastDLNAServer.java...
"C:\Program Files\eclipse adoptium\jdk-21.0.4.7-hotspot\bin\javac.exe" -encoding UTF-8 FastDLNAServer.java

echo Starting FastIPTV (HMP5000) DLNA Server in the background...
start "" "C:\Program Files\eclipse adoptium\jdk-21.0.4.7-hotspot\bin\javaw.exe" -Xmx96m -cp C:\IPTV FastDLNAServer

echo.
echo ========================================================
echo   FastIPTV (HMP5000) is now running on port 8899!
echo   You can close this window and open UPnP on your TV.
echo ========================================================
timeout /t 3 >nul
