import os
import glob
import json
from pathlib import Path
import sys

sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data

dirs = [
    r"C:\Users\sures\Downloads\QP",
    r"C:\Users\sures\Downloads\Done-20261101T034135Z-1-001\Done",
    r"C:\Users\sures\Downloads\10.07.26FN-20261101T035950Z-1-001\10.07.26FN"
]

db = {}
all_records = []
for d in dirs:
    if not os.path.exists(d):
        continue
    for f in sorted(glob.glob(os.path.join(d, "*.pdf"))):
        data = extract_qp_data(f)
        all_records.append(data)
        if data.get("qp_code"):
            key = data["qp_code"].replace("D ", "").strip()
            db[key] = {
                "qp_code": data["qp_code"],
                "course_name": data["course_name"],
                "course_code": data["course_code"],
                "full_course_title": data["full_course_title"],
                "syllabus_year": data["syllabus_year"],
                "syllabus_text": data["syllabus_text"],
                "degree_or_subject": data["degree_or_subject"],
                "examination": data["examination"],
                "exam_type": data.get("exam_type"),
                "exam_date": data.get("exam_date"),
                "exam_time": data.get("exam_time"),
                "filename_course_name": data.get("filename_course_name"),
                "filename_course_code": data.get("filename_course_code"),
                "filename_raw_parts": data.get("filename_raw_parts", []),
            }

target_file = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\qp_db.js"
with open(target_file, "w", encoding="utf-8") as jf:
    jf.write("var QP_DATABASE = " + json.dumps(db, indent=2, ensure_ascii=False) + ";\n")
    jf.write('if (typeof window !== "undefined") { window.QP_DATABASE = QP_DATABASE; }\n')

static_file = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\static\qp_db.js"
with open(static_file, "w", encoding="utf-8") as jf:
    jf.write("var QP_DATABASE = " + json.dumps(db, indent=2, ensure_ascii=False) + ";\n")
    jf.write('if (typeof window !== "undefined") { window.QP_DATABASE = QP_DATABASE; }\n')

print(f"Total DB entries: {len(db)} across both folders")
print("131794 in DB:", db.get("131794"))
print("131795 in DB:", db.get("131795"))

