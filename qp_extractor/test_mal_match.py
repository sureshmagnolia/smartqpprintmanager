import sys
sys.stdout.reconfigure(encoding='utf-8')
from matcher import score_qp_to_seating
from extractor import extract_qp_data
from pathlib import Path

room_entry = {
    'courseName': 'MAL5CJ303 - Literary Theories-Indian [Malayalam 2024 syllabus]',
    'stream': 'Regular',
    'date': '29.10.2026',
    'time': '02:00 PM'
}
qp = extract_qp_data(Path(r'C:\Users\sures\Downloads\29-10\153389_1793255677936.pdf'))
score_strict, status_strict, details_strict = score_qp_to_seating(room_entry, qp, mode='strict')
print('Strict:', score_strict, status_strict, details_strict)
score_dept, status_dept, details_dept = score_qp_to_seating(room_entry, qp, mode='department')
print('Dept:', score_dept, status_dept, details_dept)
