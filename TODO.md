# Smart QP Print Manager - Roadmap & Enhancements

This document outlines planned and proposed features to enhance the Smart QP Print Manager, specifically for high-volume academic environments (e.g., printing question papers).

## 🚀 High Priority (Accuracy & Organization)
- [ ] **Smart Subject Detection:** Implement Regex-based metadata extraction to automatically identify "Subject Code", "Course Name", and "Semester" from the first page of PDFs.
- [ ] **Job Separator Pages:** Automatically generate and insert cover pages between different subjects to simplify physical sorting.
- [ ] **Print Auditing:** Create a persistent log (CSV/PDF) of all print jobs, including timestamps, file metadata, and printer status for accountability.

## 🛠️ Medium Priority (Efficiency & Quality)
- [ ] **Gutter/Binding Offsets:** Add customizable margins in Booklet mode to ensure text is not lost in the fold/staple.
- [ ] **Automated Watermarking:** Add a feature to overlay "CONFIDENTIAL" or "ORIGINAL" watermarks on question papers.
- [ ] **Batch Load Balancing:** Automatically distribute large copy counts (e.g., 500 copies) across multiple identical printers to reduce total print time.

## 🔒 Security & Maintenance
- [ ] **Encrypted PDF Support:** Improve handling of password-protected files within the analysis and print pipeline.
- [ ] **Secure Temp Cleanup:** Ensure all temporary files (split chunks, generated booklets) are wiped immediately after the print job finishes.
- [ ] **Cost Estimation:** Add a reporting module to estimate toner and paper usage based on print history.

## ✅ Completed
- [x] **Restricted Environment Persistence (v3.0.3):** Fixed an issue where session data was lost when installed in read-only directories (e.g., C:\Program Files) by using the user's AppData folder. Updated default printer logic.
- [x] **Smart Room-Wise Router (v2.5.1):** Added a new tab to handle room-wise routing and parallel printing based on seating summary JSONs. Branded with custom icon and improved installer upgrade logic.
- [x] **JSON Copy Count Update (v2.4):** Added a feature to upload a JSON file to automatically update the copy counts of PDFs in the queue based on QP codes extracted from filenames.
- [x] **Sequential Print Queue (v2.1):** Replaced multi-threaded printing with a single-threaded sequential queue to prevent crashes during large file operations.
- [x] **Portable Packaging (v2.0):** Added `jpackage` support to bundle a JRE for zero-dependency distribution.
