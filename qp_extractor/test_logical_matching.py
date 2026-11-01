import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\sures\.gemini\antigravity\scratch\qp_extractor')
from extractor import extract_qp_data
from matcher import match_all_rooms

# Load the 18 PDFs from 30-10-26
dir30 = r'C:\Users\sures\Downloads\30-10-26'
pdf_paths = [os.path.join(dir30, f) for f in os.listdir(dir30) if f.endswith('.pdf')]
qp_records = [extract_qp_data(p) for p in pdf_paths]

# Load seating json
json_path = os.path.join(dir30, 'Room_Wise_Seating_Summary_2026-10-30.json')
with open(json_path, 'r', encoding='utf-8') as f:
    seating = json.load(f)

matched, dist, summary = match_all_rooms(seating, qp_records)
print('SUMMARY ON 30-10-26 (18 PDFs):')
print(summary)
print('\nMATCHED ENTRIES BREAKDOWN:')
for idx, m in enumerate(matched):
    rs = m.get('roomSerial')
    cn = m.get('courseName')
    st = m.get('matchStatus')
    qp = m.get('matchedQpCode')
    score = m.get('matchScore')
    fn = m.get('matchedPdfFilename')
    reasons = ', '.join(m.get('matchReasons', []))
    if st in ('EXACT', 'PARTIAL'):
        print(f"#{idx+1:02d} Room {rs:02d} [{st}] QP: {qp} ({score} pts) | {cn} | Source: {fn}")
        print(f"     Criteria: {reasons}")
    else:
        print(f"#{idx+1:02d} Room {rs:02d} [UNMATCHED] {cn}")

