@echo off
cd /d "%~dp0"
set VERSION=7.16
set APPNAME=Smart QP Print Manager
set PATH=C:\Users\sures\WiX;C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin;%PATH%

echo Building Maven Project...
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo Maven build failed.
    exit /b %ERRORLEVEL%
)

echo Cleaning input folders...
if not exist jpackage_input mkdir jpackage_input
del /q jpackage_input\*.jar
copy /Y target\java-print-manager-%VERSION%.jar jpackage_input\

echo Creating Native App-Image...
rmdir /s /q "output\%APPNAME%"
jpackage --type app-image --name "%APPNAME%" --app-version %VERSION% --input jpackage_input --main-jar java-print-manager-%VERSION%.jar --main-class com.printmanager.Launcher --dest output --icon src\main\resources\icon.ico
copy config.default.json "output\%APPNAME%\config.json"
mkdir "output\%APPNAME%\bin\chromium"
xcopy /E /I /Y "C:\Users\sures\.jcef-bundle\*" "output\%APPNAME%\bin\chromium\"
echo De-bloating Chromium locales...
for /f "delims=" %%f in ('dir /b "output\%APPNAME%\bin\chromium\locales\*.pak" ^| findstr /v /i "en-US.pak"') do del "output\%APPNAME%\bin\chromium\locales\%%f"

rem Skipping Native ZIP as requested

echo Creating MSI Installer...
jpackage --type msi --name "%APPNAME%" --app-version %VERSION% --app-image "output\%APPNAME%" --dest output --icon src\main\resources\icon.ico --win-shortcut --win-menu --win-dir-chooser --win-upgrade-uuid f1a8c9e5-a6b7-4c8d-9e0f-e62c1a234b5d
copy "output\%APPNAME%-%VERSION%.msi" "%APPNAME%-V%VERSION%.msi"

echo Packaging Complete.
