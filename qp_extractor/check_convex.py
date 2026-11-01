import glob
import sys
import os
import fitz

sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data

sys.stdout.reconfigure(encoding="utf-8")

folder_30 = r"C:\Users\sures\Downloads\30-10-26"
pdf_files = sorted(glob.glob(os.path.join(folder_30, "*.pdf")))

print(f"=== CHECKING EXTRACTED DATA FROM ALL 18 PDFS IN {folder_30} ===\n")

found_in_any = False
for p in pdf_files:
    fname = os.path.basename(p)
    d = extract_qp_data(p)
    
    qp_code = d.get('qp_code')
    cc = d.get('course_code')
    cn = d.get('course_name')
    full_t = d.get('full_course_title')
    
    # Also check raw text of all pages
    doc = fitz.open(p)
    raw_text = ' '.join(page.get_text() for page in doc)
    
    has_in_title = 'convex' in str(cn).lower() or 'optimization' in str(cn).lower() or 'convex' in str(full_t).lower()
    has_in_body = 'convex' in raw_text.lower() or 'optimization' in raw_text.lower()
    
    if has_in_title or has_in_body:
        found_in_any = True
        print(f"[FOUND MATCH/KEYWORD] {fname}:")
        print(f"   QP Code: {qp_code}")
        print(f"   Extracted Course Code: {cc}")
        print(f"   Extracted Course Name: {cn}")
        print(f"   Full Title: {full_t}")
        print(f"   Keyword in Title: {has_in_title}")
        print(f"   Keyword in Body: {has_in_body}\n")
    else:
        print(f"{fname}: QP={qp_code} | Title='{cn}' | Code={cc}")

print("\n--------------------------------------------------------------")
print("=== CHECKING ALL PREVIOUSLY SAVED EXTRACTED JSON FILES ===")
for jfile in [r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\extracted_qp_data.json",
              r"C:\Users\sures\Downloads\Room_Wise_Seating_Summary_Populated_2026-11-01.json"]:
    if os.path.exists(jfile):
        import json
        with open(jfile, "r", encoding="utf-8") as f:
            jdata = json.load(f)
        matches = [x for x in jdata if 'convex' in str(x).lower() or 'optimization' in str(x).lower()]
        print(f"{os.path.basename(jfile)}: {len(matches)} occurrences of 'convex'/'optimization'")
        for m in matches[:3]:
            print("  Example:", m.get('courseName') or m.get('course_name') or m.get('filename'))

