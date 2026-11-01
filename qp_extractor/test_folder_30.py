import sys
import os
import json
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data

folder = Path(r"C:\Users\sures\Downloads\30-10-26")
json_path = folder / "Room_Wise_Seating_Summary_2026-11-01.json"

print("=== ROOM WISE SEATING SUMMARY JSON ===")
with open(json_path, encoding='utf-8') as f:
    room_data = json.load(f)
print(json.dumps(room_data, indent=2))

print("\n=== EXTRACTED PDF DATA FROM 30-10-26 ===")
pdf_records = []
for p in sorted(folder.glob("*.pdf")):
    d = extract_qp_data(p)
    pdf_records.append(d)
    print(f"{p.name[:25]:<25} | QP: {str(d.get('qp_code')):<9} | Code: {str(d.get('course_code')):<22} | Name: {str(d.get('course_name'))[:30]:<30} | Year: {str(d.get('syllabus_year'))}")


