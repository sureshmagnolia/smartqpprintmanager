import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data
import glob

# Load 18 PDFs
pdf_dir = r"C:\Users\sures\Downloads\30-10-26"
pdfs = [extract_qp_data(f) for f in sorted(glob.glob(os.path.join(pdf_dir, "*.pdf")))]

# Load Seating JSON
with open(os.path.join(pdf_dir, "Room_Wise_Seating_Summary_2026-10-30.json"), "r", encoding="utf-8") as f:
    rooms = json.load(f)

from matcher import match_all_rooms

print("--- MATCHING SEATING WITH 18 PDFS ---")
results, qp_dist, stats = match_all_rooms(rooms, pdfs)

matched_count = 0
unmatched_count = 0
matched_courses = set()
unmatched_courses = set()

for res in results:
    status = res.get("matchStatus", "UNMATCHED")
    c_name = res.get("courseName")
    pdf_fn = res.get("pdfFileName")
    matched_file = res.get("matchedPdfFilename")
    qp_code = res.get("qpCode")
    reasons_str = "; ".join(res.get("matchReasons") or [])
    score = res.get("matchScore")
    
    if status in ("FULL_MATCH", "EXACT"):
        matched_count += 1
        matched_courses.add((c_name, qp_code, matched_file, score))
    else:
        unmatched_count += 1
        unmatched_courses.add((c_name, pdf_fn, status, reasons_str, qp_code, matched_file, score))

print(f"Total Room Allocations: {len(results)}")
print(f"Full Matches: {matched_count}")
print(f"Unmatched: {unmatched_count}")

print("\n--- COURSES WITH EXACT MATCHES ---")
for c, qp, mf, sc in sorted(matched_courses, key=lambda x: str(x[0])):
    print(f"MATCHED: {c} -> {qp} ({mf}) [Score: {sc}]")

print("\n--- COURSES NOT EXACT (PARTIAL OR UNMATCHED) ---")
for c, fn, status, reasons, qp, mf, sc in sorted(unmatched_courses, key=lambda x: str(x[0])):
    print(f"[{status}] {c}\n   Expected PDF: {fn}\n   Best Matched QP: {qp} ({mf}) [Score: {sc}]\n   Reasons: {reasons}\n")

