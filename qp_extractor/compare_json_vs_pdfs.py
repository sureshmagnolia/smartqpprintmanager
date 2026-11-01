import json
import glob
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data
from matcher import parse_seating_coursename

with open(r"C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json", "r", encoding="utf-8") as f:
    rooms = json.load(f)

json_courses = {}
for r in rooms:
    c = r['courseName']
    fn = r.get('pdfFileName', '')
    parsed = parse_seating_coursename(c, fn)
    json_courses.setdefault(c, {
        'rooms': [],
        'candidates': 0,
        'parsed': parsed,
        'pdfFileName': fn
    })
    json_courses[c]['rooms'].append(r['roomSerial'])
    json_courses[c]['candidates'] += int(r.get('count', 0) or 0)

pdf_files = sorted(glob.glob(r"C:\Users\sures\Downloads\30-10-26\*.pdf"))
pdfs = [extract_qp_data(p) for p in pdf_files]

print("=== 26 UNIQUE COURSES IN SEATING JSON ===")
for c, d in sorted(json_courses.items()):
    p = d['parsed']
    print(f"JSON Course: {c}")
    print(f"  Parsed Code: {p['codes']} (norm: {p['normalized_codes']}) | Title: '{p['title']}' | Year: {p['syllabus_year']}")
    print(f"  pdfFileName: {d['pdfFileName']}")
    print(f"  Rooms ({len(d['rooms'])}): {d['rooms']} | Candidates: {d['candidates']}\n")

print("\n=== 18 QUESTION PAPERS IN FOLDER ===")
for p in pdfs:
    print(f"{p['filename']}: QP={p['qp_code']} | Code={p['course_code']} | Title='{p['course_name']}' | Year={p['syllabus_year']}")

