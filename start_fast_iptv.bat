@echo off
cd /d C:\IPTV
"C:\Program Files\eclipse adoptium\jdk-21.0.4.7-hotspot\bin\javac.exe" -encoding UTF-8 FastDLNAServer.java
start "" "C:\Program Files\eclipse adoptium\jdk-21.0.4.7-hotspot\bin\javaw.exe" -Xmx96m -cp C:\IPTV FastDLNAServer
echo FastIPTV (HMP5000) DLNA Server started on port 8899.
