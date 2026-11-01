import os, glob, json, sys
sys.stdout.reconfigure(encoding='utf-8')
from pathlib import Path
from extractor import extract_qp_data
from matcher import match_all_rooms, normalize_code, normalize_title

folder = Path(r"C:\Users\sures\Downloads\29-10")

print("======================================================================")
print(f"ANALYZING FOLDER: {folder}")
print("======================================================================")

# 1. Inspect JSON file
json_files = list(folder.glob("*.json"))
print(f"JSON files found: {[f.name for f in json_files]}")

if not json_files:
    print("No JSON files found!")
    sys.exit(1)

json_path = json_files[0]
with open(json_path, encoding='utf-8') as f:
    seating_entries = json.load(f)

print(f"\nTotal seating entries in {json_path.name}: {len(seating_entries)}")

# Summarize unique courses in seating JSON
unique_courses = {}
for entry in seating_entries:
    cname = entry.get('courseName')
    stream = entry.get('stream')
    date = entry.get('date')
    time = entry.get('time')
    key = (cname, stream, date, time)
    if key not in unique_courses:
        unique_courses[key] = {'count': 0, 'candidates': 0, 'rooms': []}
    unique_courses[key]['count'] += 1
    unique_courses[key]['candidates'] += entry.get('count', 0)
    unique_courses[key]['rooms'].append(entry.get('roomSerial'))

print(f"Unique Course Allocations in JSON: {len(unique_courses)}")
for (cname, stream, date, time), info in unique_courses.items():
    print(f" - [{stream}] {cname} | Date: {date} {time} | Rooms: {info['rooms']} | Total QPs: {info['candidates']}")

# 2. Inspect and Extract all PDFs
pdf_files = sorted(folder.glob("*.pdf"))
print(f"\nTotal PDF files found: {len(pdf_files)}")

extracted_qps = []
seen_qp_codes = {}
for p in pdf_files:
    data = extract_qp_data(p)
    extracted_qps.append(data)
    qp = data.get('qp_code')
    code = data.get('course_code')
    title = data.get('course_name')
    year = data.get('syllabus_year')
    print(f"PDF: {p.name:<25} -> QP: {str(qp):<10} | Code: {str(code):<20} | Title: {str(title):<35} | Year: {year}")

# 3. Run Matching (Strict Mode & Logical Matching)
print("\n" + "=" * 70)
print("RUNNING LOGICAL & STRICT 3-KEY MATCHER")
print("=" * 70)

matched_entries, qp_dist, summary = match_all_rooms(seating_entries, extracted_qps, mode="strict")

print("\n--- MATCHING SUMMARY ---")
for k, v in summary.items():
    print(f"  {k}: {v}")

print("\n--- DETAILED ROOM MATCH RESULTS ---")
unmatched_list = []
matched_list = []
for m in matched_entries:
    status = m.get('matchStatus')
    room = m.get('roomSerial')
    cname = m.get('courseName')
    qp = m.get('matchedQpCode')
    pdf = m.get('matchedPdfFilename')
    details = m.get('matchDetails')
    if status == 'EXACT':
        matched_list.append((room, cname, qp, pdf, details))
    else:
        unmatched_list.append((room, cname, details))

print(f"\n✓ MATCHED ENTRIES ({len(matched_list)}):")
for r, c, qp, pdf, d in matched_list:
    print(f"  Room {r:<2} | QP: {qp:<10} | File: {pdf:<25} | Course: {c}")

print(f"\n❌ UNMATCHED ENTRIES ({len(unmatched_list)}):")
for r, c, d in unmatched_list:
    print(f"  Room {r:<2} | Course: {c} | Details: {d}")

