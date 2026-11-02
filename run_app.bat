@echo off
cd /d "D:\smartqpprintmanager"
"C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe" -Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=25 -XX:G1PeriodicGCInterval=10000 -Dfile.encoding=UTF-8 -jar "D:\smartqpprintmanager\target\java-print-manager-7.20.jar"
