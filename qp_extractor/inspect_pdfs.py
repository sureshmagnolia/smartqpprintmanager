import glob
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data

files = sorted(glob.glob(r"C:\Users\sures\Downloads\30-10-26\*.pdf"))
print(f"Total PDFs: {len(files)}")
for f in files:
    d = extract_qp_data(f)
    print(
        f"{os.path.basename(f)} -> QP:{d.get('qp_code')} | CC:{d.get('course_code')} | YR:{d.get('syllabus_year')} | TITLE:{d.get('course_name')}"
    )

