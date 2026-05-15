# Smart Print Manager v2.5

A standalone Java-based print manager that automatically routes PDF files to specific printers based on their page count.

## Features
- **Smart Room-Wise Router (v2.5):** Route files to printers based on room-wise seating summaries. Supports parallel printing to 10+ printers.
- **Automatic Routing:** Define rules to send 1-page files to one printer, 2-page files to another, etc.
- **Duplex Support:** Enable duplex printing for specific page ranges.
- **Bulk Printing:** Upload multiple PDFs at once and print them all with one click.
- **Persistent Settings:** Your rules are saved automatically in `config.json`.

## Requirements
- Java 17 or higher (if running from JAR)
- No requirements for Portable version (includes JRE)

## How to Run
### Portable Version (Recommended)
1. Extract `SmartPrintManager-Portable-V2.1.zip`.
2. Run `SmartPrintManager.exe`.

### From Source
1. Open a terminal in this directory.
2. Run the following command:
   ```bash
   mvn javafx:run
   ```

## How to Package as a Standalone JAR
1. Run the build command:
   ```bash
   mvn clean package
   ```
2. The standalone JAR will be created in the `target/` directory as `java-print-manager-2.1.jar`.
3. Run it using:
   ```bash
   java -jar target/java-print-manager-2.1.jar
   ```

## Roadmap & Enhancements
Check out [TODO.md](TODO.md) for planned features designed for mass printing environments (Subject detection, separator pages, etc.).

## Setup Instructions
1. **Fetch Printers:** The app automatically detects all printers installed on your Windows machine.
2. **Define Rules:**
   - Go to the **Settings** tab.
   - Enter the page range (e.g., Min: 1, Max: 1).
   - Select the target printer from the dropdown.
   - Check "Duplex" if desired.
   - Click "Add Rule".
3. **Print:**
   - Go to the **Print Queue** tab.
   - Click "Add PDFs" to select your files.
   - The app will automatically assign each file to a printer based on your rules.
   - Click "Print All" to start the process.
