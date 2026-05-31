# Smart QP Print Manager - Development Guide

## Build & Launch Instructions
Since `mvn` is not in the system PATH, use the Maven executable bundled with IntelliJ IDEA.

### Command to Build and Run
Execute the following in PowerShell from the project root:
```powershell
Set-Location 'C:\Users\sures\smartqpprintmanager'; & "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin\mvn.cmd" clean compile javafx:run
```

## Architecture & Workflows

### Temp-First Isolation (v5.2+)
All PDF mutations (Splitting, Manual Subtraction, Overlays) MUST occur in the system's temporary directory to protect the integrity of the original source folders.

- **Manual Subtraction:** Refactored to create a temporary `remain_...pdf` and `Split_...pdf`. The original file is NEVER overwritten.
- **Cleanup:** 
    - `App.deleteIfTempFile(FileItem)` handles physical disk deletion.
    - Triggered by "Clear All", individual item removal ("X"), and "Refresh/Replace" logic.
- **Temporary Paths:** The system checks for `java.io.tmpdir` to identify which files are safe to delete.

## Troubleshooting
- **JCEF Errors:** If Chromium fails to load, ensure the `cp.txt` is updated by running `.\gen-args.ps1`.
- **Maven Not Found:** If the IntelliJ path changes, search for `mvn.cmd` using:
  `Get-ChildItem -Path C:\ -Filter mvn.cmd -Recurse`
