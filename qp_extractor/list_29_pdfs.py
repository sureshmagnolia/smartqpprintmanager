import sys
sys.stdout.reconfigure(encoding='utf-8')
from pathlib import Path
from extractor import extract_qp_data

folder = Path(r"C:\Users\sures\Downloads\29-10")
pdf_files = sorted(folder.glob("*.pdf"))
print(f"Total PDF files: {len(pdf_files)}")
for p in pdf_files:
    d = extract_qp_data(p)
    qp = d.get('qp_code')
    code = d.get('course_code')
    title = d.get('course_name')
    year = d.get('syllabus_year')
    print(f"{p.name:<25} -> QP: {str(qp):<10} | Code: {str(code):<25} | Title: {str(title):<40} | Year: {year}")

