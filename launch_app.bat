@echo off
echo Compiling and launching Smart QP Print Manager...
cd /d "C:\Users\sures\smartqpprintmanager"
"C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin\mvn.cmd" clean compile javafx:run
pause
