import os, sys, shutil, json
from pathlib import Path

src_code_dir = Path(r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
dest_dir = Path(r"D:\smartqpprintmanager\qp_extractor")
dest_chat_dir = dest_dir / "chat_history"
dest_media_dir = dest_chat_dir / "media"

print(f"Creating target directory: {dest_dir}")
dest_dir.mkdir(parents=True, exist_ok=True)
dest_chat_dir.mkdir(parents=True, exist_ok=True)
dest_media_dir.mkdir(parents=True, exist_ok=True)

# 1. Copy all code files and folders
print("\n--- COPYING APPLICATION CODES ---")
copied_files = []
for item in src_code_dir.iterdir():
    if item.name == "__pycache__":
        continue
    dest_path = dest_dir / item.name
    if item.is_dir():
        if dest_path.exists():
            shutil.rmtree(dest_path)
        shutil.copytree(item, dest_path)
        print(f"Copied directory: {item.name}")
        copied_files.append(item.name + "/")
    else:
        shutil.copy2(item, dest_path)
        print(f"Copied file: {item.name}")
        copied_files.append(item.name)

# 2. Copy transcript and media files
print("\n--- COPYING CHAT HISTORY & TRANSCRIPTS ---")
brain_dir = Path(r"C:\Users\sures\.gemini\antigravity\brain\79d79a36-f158-4798-9574-189e26d41320")
logs_dir = brain_dir / ".system_generated" / "logs"

for fn in ["transcript.jsonl", "transcript_full.jsonl"]:
    src_f = logs_dir / fn
    if src_f.exists():
        shutil.copy2(src_f, dest_chat_dir / fn)
        print(f"Copied log: {fn} ({src_f.stat().st_size} bytes)")

# Copy media
for m_dir in [logs_dir / "media", brain_dir / ".user_uploaded"]:
    if m_dir.exists():
        for mf in m_dir.iterdir():
            if mf.is_file():
                shutil.copy2(mf, dest_media_dir / mf.name)
                print(f"Copied media: {mf.name}")

# 3. Create run_app.bat in D:\smartqpprintmanager\qp_extractor
bat_content = """@echo off
title Smart QP Extractor & Room Seating Matcher
cd /d "%~dp0"
echo ========================================================
echo Starting Smart QP Extractor on port 5050...
echo Directory: %~dp0
echo ========================================================
python app.py
pause
"""
with open(dest_dir / "run_app.bat", "w", encoding="utf-8") as f:
    f.write(bat_content)
print("\nCreated launcher: D:\\smartqpprintmanager\\qp_extractor\\run_app.bat")

print("\n--- CODE AND CHAT MIGRATION SUCCESSFUL! ---")
