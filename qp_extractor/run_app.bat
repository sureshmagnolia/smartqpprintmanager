@echo off
title Smart QP Extractor & Room Seating Matcher
cd /d "%~dp0"
echo ========================================================
echo Starting Smart QP Extractor on port 5050...
echo Directory: %~dp0
echo ========================================================
python app.py
pause
