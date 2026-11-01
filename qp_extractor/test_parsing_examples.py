import json
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\sures\.gemini\antigravity\scratch\qp_extractor')
from matcher import parse_seating_coursename

examples = [
    'COM5EJ301(1) - Management of Financial Services [Commerce 2024 syllabus]',
    'HIN5EJ301 (1) - Hindi Sahitya mein Queer Vimarsh (Queer Discourse in Hindi Literature) [Hindi 2024   syllabus]',
    'MAL5EJ307 - Folklore [Malayalam 2024 syllabus]',
    'INTRODUCTION TO GEOMETRY AND THEORY OF EQUATIONS (MTS5B09) [2020 SYLLABUS]',
    'HEALTH PSYCHOLOGY (PSY5BO5) [2019 SYLLABUS]',
    'PUTHUMURAI ILAKKIYAVIYAL (TML5B10) [2019 SYLLABUS]',
    'INCOME TAX LAW AND PRACTICE (5.5 BCH) [2017 SYLLABUS]',
    'FINANCIAL MANAGEMENT (BCM5B11) [2019 SYLLABUS]',
    'BBA 2B 02 FINANCIAL ACCOUNTING (2019)',
    'MATHEMATICAL ECONOMICS (ECO5B10) [2019 SYLLABUS]'
]

for ex in examples:
    p = parse_seating_coursename(ex)
    print("Original:", ex)
    print("  Codes:", p['codes'], "| Norm:", p['normalized_codes'])
    print("  Title:", p['title'], "| Year:", p['syllabus_year'])
    print("---")

