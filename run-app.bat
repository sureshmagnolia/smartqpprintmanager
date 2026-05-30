@echo off
set /p CP=<cp.txt
java -cp ".;target\classes;%CP%" com.printmanager.Launcher

