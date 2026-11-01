import json
import glob
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\sures\.gemini\antigravity\scratch\qp_extractor')
from extractor import extract_qp_data
from matcher import parse_seating_coursename, normalize_code

json_path = r'C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json'
with open(json_path, 'r', encoding='utf-8') as f:
    entries = json.load(f)

# Find all PDFs in entire Downloads
downloads = r'C:\Users\sures\Downloads'
all_pdf_paths = glob.glob(os.path.join(downloads, '**', '*.pdf'), recursive=True)

# Filter candidate folders
qp_pdf_paths = [p for p in all_pdf_paths if any(k in p for k in ['30-10-26', 'QP', 'Done', '10.07.26FN'])]
print(f'Total candidate PDFs across QP folders: {len(qp_pdf_paths)}')

extracted_qps = []
for p in qp_pdf_paths:
    try:
        rec = extract_qp_data(p)
        rec['full_path'] = p
        extracted_qps.append(rec)
    except Exception as ex:
        pass

print(f'Successfully extracted metadata from {len(extracted_qps)} PDFs')

unmatched_courses = {}
found_courses = {}

for e in entries:
    cname = e.get('courseName', '')
    if cname in found_courses or cname in unmatched_courses:
        continue
    parsed = parse_seating_coursename(cname)
    scodes = parsed.get('codes', [])
    stitle = parsed.get('title', '')
    
    matches = []
    for q in extracted_qps:
        q_codes = [normalize_code(c) for c in [q.get('course_code')]]
        q_title = q.get('course_name') or ''
        
        code_match = any(sc and (sc in qc or qc in sc) for sc in scodes for qc in q_codes if qc)
        title_match = (stitle.lower() in q_title.lower() or q_title.lower() in stitle.lower()) if len(stitle) > 5 and len(q_title) > 5 else False
        
        if code_match or title_match:
            folder_name = os.path.basename(os.path.dirname(q['full_path']))
            matches.append((q.get('qp_code'), folder_name, os.path.basename(q['full_path']), q.get('course_code'), q.get('course_name')))
            
    if matches:
        found_courses[cname] = matches
    else:
        unmatched_courses[cname] = parsed

print(f"\n--- FOUND COURSES ({len(found_courses)}) ---")
for c, m in found_courses.items():
    print(f"Course: {c}")
    for match in m[:3]:
        print(f"  -> QP: {match[0]} in [{match[1]}] file: {match[2]} ({match[3]} - {match[4]})")

print(f"\n--- STILL UNMATCHED COURSES ({len(unmatched_courses)}) ---")
for c, p in unmatched_courses.items():
    print(f"Course: {c} (codes: {p.get('codes')}, title: {p.get('title')})")

