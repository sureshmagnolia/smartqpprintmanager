# Question Paper Metadata Extractor Web App

A web application designed for extracting structured metadata from Question Paper PDFs (specialized for Calicut University format).

## Extracted Fields
- **QP Code**: Formatted as `D 152087`, `D 153242`, etc.
- **Course Name**: e.g., `CO-OPERATIVE THEORY AND PRACTICE`, `QUANTUM MECHANICS-I`, `WORLD REGIONAL GEOGRAPHY`.
- **Course Code**: e.g., `BCM 5B 10`, `PHY5CJ303/APH5CJ303`, `MAT5EJ301(1)`.
- **Syllabus Year**: e.g., `2019`, `2020`, `2021-2023`, `2024`.
- **Degree / Subject**: e.g., `B.Com.`, `Botany`, `ACCOUNTING AND FINANCE / COMMERCE`.
- **Full Examination**: e.g., `FIFTH SEMESTER (CBCSS—UG) DEGREE EXAMINATION, NOVEMBER 2026`.

---

## How to Run

### Method 1: Double-click Batch File
Double-click `run_app.bat` in this folder. It starts the server and automatically opens your web browser to:
[http://localhost:8080](http://localhost:8080)

### Method 2: Python Command Line
```powershell
cd C:\Users\sures\.gemini\antigravity\scratch\qp_extractor
python app.py
```

---

## Features
1. **Drag-and-Drop & Multi-file Upload**:
   Drop single or dozens of `.pdf` question papers directly onto the interface.
2. **Instant "Load 45 Sample QPs" Button**:
   One click to load and parse all files currently in `C:\Users\sures\Downloads\QP`.
3. **Structured Table & Realtime Search**:
   Filter instantly by text or dropdown selector for syllabus years.
4. **Export Capabilities**:
   - **CSV Export**: Downloads a UTF-8 with BOM CSV ready for Excel with full regional script support (Malayalam, Tamil).
   - **JSON Export**: Raw structured JSON.
   - **Copy to Clipboard**: Quick tab-separated copy for Google Sheets or Excel.
5. **Detail Drawer**:
   Click "Details" on any row to view full metadata breakdown.
6. **Zero External Heavy Dependencies**:
   Uses PyMuPDF (`fitz`) and Bottle (already installed), with native Windows Media OCR fallback for vector-curve PDFs.

