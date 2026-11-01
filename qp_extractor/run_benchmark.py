import sys
import glob
import os
import json
import pandas as pd

sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
sys.stdout.reconfigure(encoding="utf-8")
from extractor import extract_qp_data

def main():
    folder = r"C:\Users\sures\Downloads\QP"
    files = sorted(glob.glob(os.path.join(folder, "*.pdf")))
    print(f"Processing {len(files)} files from {folder}...\n")

    results = []
    success_count = 0

    for idx, f in enumerate(files):
        data = extract_qp_data(f)
        results.append(data)
        
        has_code = bool(data["qp_code"])
        has_course = bool(data["course_name"])
        has_year = bool(data["syllabus_year"])
        
        ok = has_code and has_course and has_year
        if ok:
            success_count += 1
            status = "✅ OK  "
        else:
            status = "❌ FAIL"
            
        code_str = data["qp_code"] or "MISSING"
        year_str = data["syllabus_year"] or "MISSING"
        course_str = (data["course_name"] or "MISSING")
        if len(course_str) > 35:
            course_str = course_str[:32] + "..."
        c_code_str = data["course_code"] or ""

        print(f"[{idx+1:02d}] {status} | Code: {code_str:8s} | Year: {year_str:6s} | {course_str:35s} [{c_code_str}]")

    print("\n" + "="*80)
    print(f"Benchmark Results: {success_count}/{len(files)} files successfully extracted ({success_count/len(files)*100:.1f}%)")
    print("="*80)

    # Save to CSV
    df = pd.DataFrame(results)
    csv_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\extracted_qp_data.csv"
    df.to_csv(csv_path, index=False, encoding="utf-8-sig")
    print(f"\nSaved results to CSV: {csv_path}")

    # Save to JSON
    json_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\extracted_qp_data.json"
    with open(json_path, "w", encoding="utf-8") as jf:
        json.dump(results, jf, indent=2, ensure_ascii=False)
    print(f"Saved results to JSON: {json_path}")

if __name__ == "__main__":
    main()

