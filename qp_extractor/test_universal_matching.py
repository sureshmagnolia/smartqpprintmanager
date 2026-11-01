import json
import glob
import os
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")

from extractor import extract_qp_data
from matcher import match_all_rooms, parse_seating_coursename

# 1. Test Course Name Parser on arbitrary diverse strings
sample_course_names = [
    "CODING THEORY (MTH 3E01) [2020 SYLLABUS]",
    "INVESTMENT MANAGEMENT (MCM3EF01) [2020 SYLLABUS]",
    "BASIC SANSKRIT (SKT 3E08) (MAL3 C09) [2020 SYLLABUS]",
    "COM5EJ301(1) - Management of Financial Services [Commerce 2024 syllabus]",
    "CSC5EJ303 (2) - Machine Learning Algorithms [Computer Science 2024 syllabus]",
    "CHE5CJ303 - ORGANIC CHEMISTRY - III [Chemistry 2024 syllabus]",
    "CO-OPERATIVE THEORY AND PRACTICE (BCM5B10) [2019 SYLLABUS]",
    "FINANCIAL MARKETS AND SERVICES (BCM5B10) [2019 SYLLABUS]",
    "PERFORMANCE MANAGEMENT (5.4 BCH) [2017 SYLLABUS]",
    "INTRODUCTION TO GEOMETRY AND THEORY OF EQUATIONS (MTS5B09) [2020 SYLLABUS]",
    "BUSINESS ECONOMICS--(ECO4 E02)",
    "SCIENCE AND TECHNOLOGY IN MEDIEVAL INDIA--(HIS4E03)",
    "FOLKLORE--(MAL4 E16)",
    "ADVANCED STRATEGIC COST ACCOUNTING (MCM4 E01) [2020 ADMISSION ONWARDS]",
    "COMMUTATIVE ALGEBRA (MTH 4E05) [2020 SYLLABUS]",
    "RESEARCH METHODOLOGY (MCM3C13) [2020 SYLLABUS]",
    "FUNCTIONAL ANALYSIS (MTH 3C13) [2020 SYLLABUS]",
    "MICROBIOLOGY & IMMUNOLOGY",
    "MATHEMATICS - I (MAT1C01)",
    "BBA 2B 02 FINANCIAL ACCOUNTING (2019)"
]

print("=== 1. TESTING DYNAMIC COURSE NAME PARSING (ZERO HARDCODING) ===")
for cn in sample_course_names:
    p = parse_seating_coursename(cn)
    print(f"Input: {cn}")
    print(f"  Title: '{p['title']}' | Codes: {p['codes']} (Norm: {p['normalized_codes']}) | Year: '{p['syllabus_year']}'\n")

# 2. Test matching on multiple real-world pairs
print("\n=== 2. BENCHMARKING MULTIPLE SEATING JSONs & PDF FOLDERS ===")

# Collect all PDFs from QP, Done, 10.07.26FN, and 30-10-26
all_pdf_paths = (
    glob.glob(r"C:\Users\sures\Downloads\QP\*.pdf") +
    glob.glob(r"C:\Users\sures\Downloads\Done-20261101T034135Z-1-001\Done\*.pdf") +
    glob.glob(r"C:\Users\sures\Downloads\10.07.26FN-20261101T035950Z-1-001\10.07.26FN\*.pdf") +
    glob.glob(r"C:\Users\sures\Downloads\30-10-26\*.pdf")
)
# De-duplicate
all_pdf_paths = list(dict.fromkeys(all_pdf_paths))
print(f"Total Unique PDFs Collected across Downloads: {len(all_pdf_paths)}")

all_qp_records = [extract_qp_data(p) for p in all_pdf_paths]
print(f"Extracted metadata from all {len(all_qp_records)} PDFs.")

# Test against each Seating JSON
seating_files = [
    r"C:\Users\sures\Downloads\Room_Wise_Seating_Summary_2026-11-01 (1).json",
    r"C:\Users\sures\Downloads\Room_Wise_Seating_Summary_2026-10-22.json",
    r"C:\Users\sures\Downloads\Room_Wise_Seating_Summary_2026-10-28.json",
    r"C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json"
]

for s_file in seating_files:
    if not os.path.exists(s_file):
        continue
    with open(s_file, encoding='utf-8') as f:
        s_data = json.load(f)
    matched, dist, summary = match_all_rooms(s_data, all_qp_records)
    print(f"\n--- Results for {os.path.basename(s_file)} ({len(s_data)} entries) ---")
    print(f"  Exact: {summary['exactMatches']} | Partial: {summary['partialMatches']} | Unmatched: {summary['unmatchedEntries']}")
    print(f"  Unique QPs Matched: {summary['uniqueQpsMatched']}")
    print(f"  Total Copies Required: {sum(d['totalRequired'] for d in dist)}")

