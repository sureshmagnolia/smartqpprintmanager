@echo off
dir /s /b src\main\java\*.java > java_files.txt
set /p CP=<cp.txt
javac -cp ".;%CP%" -d target\classes @java_files.txt
