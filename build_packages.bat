@echo off
set VERSION=6.2
set APPNAME=Smart QP Print Manager
set PATH=C:\Users\sures\WiX;%PATH%

echo Cleaning input folders...
del /q jpackage_input\*.jar
copy /Y target\java-print-manager-%VERSION%.jar jpackage_input\

echo Creating Native App-Image...
rmdir /s /q "output\%APPNAME%"
jpackage --type app-image --name "%APPNAME%" --app-version %VERSION% --input jpackage_input --main-jar java-print-manager-%VERSION%.jar --main-class com.printmanager.Launcher --dest output --icon src\main\resources\icon.ico
copy config.default.json "output\%APPNAME%\config.json"
mkdir "output\%APPNAME%\bin\chromium"
xcopy /E /I /Y "C:\Users\sures\.jcef-bundle\*" "output\%APPNAME%\bin\chromium\"

rem Skipping Native ZIP as requested

echo Creating MSI Installer...
jpackage --type msi --name "%APPNAME%" --app-version %VERSION% --app-image "output\%APPNAME%" --dest output --icon src\main\resources\icon.ico --win-shortcut --win-menu --win-dir-chooser --win-upgrade-uuid f1a8c9e5-a6b7-4c8d-9e0f-e62c1a234b5d
copy "output\%APPNAME%-%VERSION%.msi" "%APPNAME%-V%VERSION%.msi"

echo Packaging Complete.
