@echo off
set VERSION=3.2.3
set APPNAME=Smart QP Print Manager
set PATH=C:\Users\sures\WiX;%PATH%

echo Creating input directory...
rmdir /s /q jpackage_input
mkdir jpackage_input
copy target\java-print-manager-%VERSION%.jar jpackage_input\

echo Creating Portable ZIP...
rmdir /s /q dist-v%VERSION%-portable
mkdir dist-v%VERSION%-portable
mkdir dist-v%VERSION%-portable\bin\chromium
copy target\java-print-manager-%VERSION%.jar dist-v%VERSION%-portable\
copy run-app.bat dist-v%VERSION%-portable\
copy config.default.json dist-v%VERSION%-portable\config.json
copy README.md dist-v%VERSION%-portable\

echo Copying Chromium Bundle (this may take a minute)...
xcopy /E /I /Y "C:\Users\sures\.jcef-bundle\*" "dist-v%VERSION%-portable\bin\chromium\"

powershell.exe -NoProfile -Command "Compress-Archive -Path 'dist-v%VERSION%-portable\*' -DestinationPath '%APPNAME%-Portable-V%VERSION%.zip' -Force"

echo Creating Native App-Image...
rmdir /s /q "output\%APPNAME%"
jpackage --type app-image --name "%APPNAME%" --app-version %VERSION% --input jpackage_input --main-jar java-print-manager-%VERSION%.jar --main-class com.printmanager.Launcher --dest output --icon src\main\resources\icon.ico
copy config.default.json "output\%APPNAME%\config.json"
mkdir "output\%APPNAME%\bin\chromium"
xcopy /E /I /Y "C:\Users\sures\.jcef-bundle\*" "output\%APPNAME%\bin\chromium\"

powershell.exe -NoProfile -Command "Compress-Archive -Path 'output\%APPNAME%\*' -DestinationPath '%APPNAME%-Native-V%VERSION%.zip' -Force"

echo Creating EXE Installer...
jpackage --type exe --name "%APPNAME%" --app-version %VERSION% --app-image "output\%APPNAME%" --dest output --icon src\main\resources\icon.ico --win-shortcut --win-menu --win-dir-chooser
copy "output\%APPNAME%-%VERSION%.exe" "%APPNAME%-V%VERSION%.exe"

echo Creating MSI Installer...
jpackage --type msi --name "%APPNAME%" --app-version %VERSION% --app-image "output\%APPNAME%" --dest output --icon src\main\resources\icon.ico --win-shortcut --win-menu --win-dir-chooser
copy "output\%APPNAME%-%VERSION%.msi" "%APPNAME%-V%VERSION%.msi"

echo Packaging Complete.

