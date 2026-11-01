import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, r'C:\Users\sures\.gemini\antigravity\scratch\qp_extractor')
from extractor import extract_qp_data
from matcher import parse_seating_coursename

def get_spaceless_codes(text):
    if not text:
        return []
    parts = re.split(r'[\/,\&]|\s+and\s+|\s+or\s+', str(text), flags=re.I)
    codes = set()
    for p in parts:
        clean = re.sub(r'[^A-Z0-9]', '', p.upper())
        if clean:
            codes.add(clean)
            no_option = re.sub(r'\([0-9]+\)', '', p)
            clean_no_opt = re.sub(r'[^A-Z0-9]', '', no_option.upper())
            if clean_no_opt:
                codes.add(clean_no_opt)
            # Handle letter O vs 0 in codes e.g. PSY5BO5 -> PSY5B05
            o_to_zero = re.sub(r'(?<=[A-Z\d])O(?=\d)', '0', clean)
            o_to_zero = re.sub(r'(?<=\d)O(?=[A-Z\d]|$)', '0', o_to_zero)
            codes.add(o_to_zero)
    return list(codes)

def clean_title(t):
    if not t:
        return ''
    t = re.sub(r'[^A-Za-z0-9\s]', ' ', str(t)).upper()
    return ' '.join(t.split())

def is_year_compatible(seat_year, qp_year):
    if not seat_year or not qp_year:
        return True, "Year neutral"
    sy = str(seat_year).strip()
    qy = str(qp_year).strip()
    if sy == qy or sy in qy:
        return True, f"Year match ({sy})"
    if sy == '2020' and any(y in qy for y in ['2021', '2022', '2023']):
        return True, f"Year cycle match ({sy} ~ {qy})"
    if sy == '2019' and any(y in qy for y in ['2020', '2021', '2022', '2023']):
        return True, f"Year cycle match ({sy} ~ {qy})"
    return False, f"Year mismatch ({sy} vs {qy})"

# Load 18 PDFs
folder = r'C:\Users\sures\Downloads\30-10-26'
pdf_paths = [os.path.join(folder, f) for f in sorted(os.listdir(folder)) if f.endswith('.pdf')]
qps = [extract_qp_data(p) for p in pdf_paths]

# Load Seating JSON
json_path = os.path.join(folder, 'Room_Wise_Seating_Summary_2026-10-30.json')
with open(json_path, 'r', encoding='utf-8') as f:
    seating = json.load(f)

print(f"Loaded {len(seating)} seating entries and {len(qps)} PDFs.")

matched_records = []

for idx, seat in enumerate(seating):
    parsed = parse_seating_coursename(seat.get('courseName', ''))
    seat_codes = set()
    for c in parsed['codes']:
        seat_codes.update(get_spaceless_codes(c))
    seat_title = clean_title(parsed['title'])
    seat_year = parsed['syllabus_year']
    
    best_qp = None
    best_score = -999
    best_status = 'UNMATCHED'
    best_reasons = []
    
    for q in qps:
        score = 0
        reasons = []
        
        # 1. Course Code (space removed) match
        qp_codes = set()
        if q.get('course_code'):
            qp_codes.update(get_spaceless_codes(q.get('course_code')))
        if q.get('filename_data', {}).get('course_code'):
            qp_codes.update(get_spaceless_codes(q['filename_data']['course_code']))
            
        code_intersect = seat_codes.intersection(qp_codes)
        code_matched = len(code_intersect) > 0
        if code_matched:
            score += 60
            reasons.append(f"Code match (spaceless: {list(code_intersect)[0]})")
        else:
            # Check for partial elective code in same department (e.g. COM5EJ301 vs COM5EJ305)
            dept_variant = False
            for sc in seat_codes:
                for qc in qp_codes:
                    if len(sc) >= 7 and len(qc) >= 7 and sc[:6] == qc[:6]:
                        dept_variant = True
                        reasons.append(f"Elective variant ({sc} ~ {qc})")
                        score += 30
                        break
                if dept_variant:
                    break
                    
        # 2. Title match
        qp_title = clean_title(q.get('course_name') or '')
        if not qp_title and q.get('filename_data', {}).get('course_name'):
            qp_title = clean_title(q['filename_data']['course_name'])
            
        title_matched = False
        if seat_title and qp_title:
            if seat_title == qp_title:
                score += 40
                title_matched = True
                reasons.append(f"Exact title match ('{seat_title}')")
            elif len(seat_title) > 6 and (seat_title in qp_title or qp_title in seat_title):
                score += 30
                title_matched = True
                reasons.append(f"Substring title match")
            else:
                s_words = set(w for w in seat_title.split() if len(w) > 3)
                q_words = set(w for w in qp_title.split() if len(w) > 3)
                common = s_words.intersection(q_words)
                if common:
                    ratio = len(common) / min(len(s_words), len(q_words))
                    if ratio >= 0.5:
                        pts = int(30 * ratio)
                        score += pts
                        title_matched = True
                        reasons.append(f"Title token overlap ({pts} pts: {common})")
                        
        # 3. Year match
        qp_year = q.get('syllabus_year')
        yr_compat, yr_reason = is_year_compatible(seat_year, qp_year)
        if yr_compat:
            score += 15
            reasons.append(yr_reason)
        else:
            score -= 25
            reasons.append(yr_reason)
            
        # Determine Status
        status = 'UNMATCHED'
        if score >= 90 or (code_matched and title_matched and yr_compat):
            status = 'EXACT'
        elif score >= 50:
            status = 'PARTIAL'
            
        if score > best_score:
            best_score = score
            best_qp = q
            best_status = status
            best_reasons = reasons
            
    matched_records.append({
        'room': seat.get('roomSerial'),
        'stream': seat.get('stream'),
        'courseName': seat.get('courseName'),
        'count': seat.get('count'),
        'status': best_status,
        'score': best_score,
        'qp_code': best_qp.get('qp_code') if best_status != 'UNMATCHED' else None,
        'pdf': best_qp.get('filename') if best_status != 'UNMATCHED' else None,
        'reasons': best_reasons
    })

exact_count = sum(1 for m in matched_records if m['status'] == 'EXACT')
partial_count = sum(1 for m in matched_records if m['status'] == 'PARTIAL')
unmatched_count = sum(1 for m in matched_records if m['status'] == 'UNMATCHED')

print(f"\nRESULTS:")
print(f"Exact Matches: {exact_count}")
print(f"Partial Matches: {partial_count}")
print(f"Unmatched: {unmatched_count}")
print("\nFirst 15 Matched Records:")
for m in matched_records[:15]:
    print(f"Room {m['room']:02d} [{m['status']:9s}] QP: {m['qp_code']} ({m['score']} pts) | {m['courseName']} | {m['pdf']}")

