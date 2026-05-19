# Smart QP Print Manager - AI Developer & Maintenance Guide

This README is designed for future AI agents and developers to understand the architecture, compilation, and release process of the Smart QP Print Manager.

### Stable Baselines
*   **Version 3.1.2 ("All is Well" Version)**: The last known fully stable version with a functioning 1-Click Sync and basic University Portal integration. If any major mishaps occur in higher versions, this is the primary fallback.

## 1. Project Architecture

### Core Components
*   **App.java**: The main JavaFX entry point and UI controller. It manages the TabPane, TableViews, and real-time status updates via JavaFX properties.
*   **PrintService.java**: Handles interaction with the Windows Print Spooler using `javax.print` and `PDFBox`. It also performs live printer status checks via PowerShell CIM queries.
*   **PrinterHealthMonitor.java**: A background service that runs a PowerShell/CIM script to check for hardware errors (Out of Paper, Jam, Offline) and updates the UI via a `StringProperty`.
*   **ConfigManager.java**: Handles JSON serialization/deserialization for persisting application state (rules, queue, college ID).

### Data Models (`com.printmanager.model`)
*   **Config.java**: Root configuration object.
*   **FileItem.java**: Represents a PDF in the print queue with its target printer, style, and copy count.
*   **RoomGroup.java / RoomItem.java**: Structures for the "Smart Room Wise Router", facilitating grouped printing.
*   **SmartSplitRule.java**: Configurable rules for splitting files based on keywords (e.g., separating MCQ parts).

## 2. Integration with Examflow
The app integrates with the **Examflow** web app via a 1-click cloud sync:
1.  **Push (Web):** Examflow stringifies seating data and uploads it to Firestore at `public_print_queue/{collegeId}`.
2.  **Pull (Java):** This app uses `HttpClient` to fetch that document via the Firestore REST API and automatically populates the queue and room router.

## 3. Compilation & Execution
The project uses **Maven** and **Java 17+**.

*   **Build:** `mvn clean compile`
*   **Run:** `mvn javafx:run`
*   **Package Shaded JAR:** `mvn clean package -DskipTests` (Generates the shaded JAR in `target/`)

## 4. Distribution Preparation (IMPORTANT)
To ensure distributions are sent to users without local trial/test data:

1.  **Clean Config Source:** Always use `config.default.json` as the base for the release.
2.  **Repackaging Logic:**
    *   Create a clean staging folder (e.g., `jpackage_input`).
    *   Copy the shaded JAR from `target/` into it.
    *   Copy `config.default.json` into the staging folder **and rename it to `config.json`**.
3.  **Generate EXE (requires WiX):**
    ```powershell
    jpackage --type exe --name "Smart QP Print Manager" --input <staging_folder> --main-jar java-print-manager-x.x.x.jar --main-class com.printmanager.Launcher --win-shortcut --win-menu --vendor "Magnolia" --app-version x.x.x --icon src/main/resources/icon.ico --dest dist-installer
    ```

## 5. Maintenance Notes (v3.1.4 Gold Standard)
*   **Robust Room Matching:** Matches files to room slots using a triple-check: regex extraction (`_(\d{5,8})_`), literal `_QP_` containment, and literal `_QP.` containment. This ensures components like `Split_...` and `Remain_...` are correctly routed.
*   **Split Component Management:** Manual splits generate two components: `Split_...` (the extracted range) and `Remain_...` (the remaining pages). The original file is removed after a successful split to maintain folder hygiene. Both components are treated as "newly added" files, triggering automatic rule re-evaluation (Style, Printer, Copies).
*   **Booklet Rendering:** strictly adheres to the 4-page foldable rule (pads to multiple of 4) to ensure proper physical folding and alignment.
*   **Reactive Room Router:** Utilizes JavaFX `Bindings` to instantly reflect changes in linked file status, name, and style within the room routing cards.
*   **Thread Safety:** Always use `Platform.runLater()` when updating UI elements from background threads.
*   **Production Logging:** Uses SLF4J/Logback for all diagnostics; `printStackTrace` and `System.out` are avoided for gold-standard observability.
