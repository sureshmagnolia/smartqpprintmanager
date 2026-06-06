# Smart QP Print Manager - Distribution Build Guide (V6.2+)

This guide documents the exact process for building the Native ZIP and MSI distributions for the Smart QP Print Manager. Use this as a reference for future builds.

## Prerequisites
- **JDK 17+** with `jpackage` available in the PATH.
- **Maven** (Path used in this session: `C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin\mvn.cmd`).
- **WiX Toolset v3.x** (Required for MSI creation, located at: `C:\Users\sures\WiX`).
- **JCEF Bundle:** Pre-compiled Chromium/JCEF binaries located at `C:\Users\sures\.jcef-bundle\`.

## Dependency Handling & Bloat Prevention

### 1. PDF & Core Dependencies (The "Uber" JAR)
All Java dependencies, including **Apache PDFBox**, Jackson (JSON), and SLF4J (Logging), are bundled into a single **shaded JAR** via the `maven-shade-plugin`. 
- **Benefit:** No external `lib/` folder is needed, ensuring the app is self-contained.
- **Verification:** The `mvn clean package` command automatically handles this.

### 2. Chromium / JCEF Integration
Since Chromium binaries are platform-specific and large, they are **not** bundled in the JAR. They are manually injected into the native app-image's `bin/chromium` folder (see Step 4).
- **Required Path:** `C:\Users\sures\.jcef-bundle\`

### 3. Preventing Distribution Bloat
To ensure the final ZIP and MSI are as small as possible:
- **Dedicated Input Folder:** We use `jpackage_input` which contains **ONLY** the production shaded JAR. This prevents `jpackage` from accidentally pulling in test-classes, source code, or unshaded dependency JARs from the `target/` directory.
- **Clean Build:** Always use `mvn clean` before packaging to ensure no stale artifacts are included.
- **App-Image Source:** Both the ZIP and MSI are generated from the same optimized `app-image`, ensuring binary consistency.

## Build Steps

### 1. Compile and Package JAR
Clean the project and build the shaded "uber" JAR:
```powershell
mvn clean package -DskipTests
```
*Note: This generates `target/java-print-manager-6.2.jar`.*

### 2. Prepare jpackage Input
Clear any old JARs from the input folder and copy the new one:
```powershell
if (Test-Path jpackage_input) { Remove-Item jpackage_input\*.jar -ErrorAction SilentlyContinue }
Copy-Item target\java-print-manager-6.2.jar jpackage_input\
```

### 3. Create Native App-Image
This creates the base folder structure for the native app:
```powershell
if (Test-Path "output\Smart QP Print Manager") { Remove-Item -Recurse -Force "output\Smart QP Print Manager" }
jpackage --type app-image --name "Smart QP Print Manager" --app-version 6.2 --input jpackage_input --main-jar java-print-manager-6.2.jar --main-class com.printmanager.Launcher --dest output --icon src\main\resources\icon.ico
```

### 4. Inject Dependencies and Configuration
Add the configuration and the Chromium runtime into the app-image:
```powershell
Copy-Item config.default.json "output\Smart QP Print Manager\config.json"
New-Item -ItemType Directory -Force "output\Smart QP Print Manager\bin\chromium"
xcopy /E /I /Y "C:\Users\sures\.jcef-bundle\*" "output\Smart QP Print Manager\bin\chromium\"
```

### 5. Generate Distributions

#### Native ZIP
```powershell
powershell.exe -NoProfile -Command "Compress-Archive -Path 'output\Smart QP Print Manager\*' -DestinationPath 'Smart QP Print Manager-Native-V6.2.zip' -Force"
```

#### MSI Installer
Ensure WiX is in the path for this command:
```powershell
$env:PATH += ";C:\Users\sures\WiX"
jpackage --type msi --name "Smart QP Print Manager" --app-version 6.2 --app-image "output\Smart QP Print Manager" --dest output --icon src\main\resources\icon.ico --win-shortcut --win-menu --win-dir-chooser --win-upgrade-uuid f1a8c9e5-a6b7-4c8d-9e0f-e62c1a234b5d
Copy-Item "output\Smart QP Print Manager-6.2.msi" "Smart QP Print Manager-V6.2.msi"
```

## Key Configuration Files
- `src/main/java/com/printmanager/App.java`: Main application logic.
- `src/main/java/com/printmanager/Launcher.java`: Splash screen and JFX entry point.
- `config.default.json`: Template for user configuration.

---
*Last updated by Gemini CLI on Tuesday, 2 June 2026.*
