import json
import re
import glob
import os
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data

def normalize_code(c):
    if not c:
        return ''
    return re.sub(r'[^A-Z0-9]', '', str(c).upper())

def extract_all_codes(text):
    if not text:
        return []
    parts = re.split(r'[\/,\&]|\s+and\s+|\s+or\s+', str(text), flags=re.IGNORECASE)
    res = []
    for p in parts:
        nc = normalize_code(p)
        if nc and len(nc) >= 3:
            res.append(nc)
            m_trail = re.match(r'^([A-Z0-9]+?)(\d+)$', nc)
            if m_trail and len(m_trail.group(1)) >= 4 and len(m_trail.group(2)) <= 2:
                res.append(m_trail.group(1))
    return list(dict.fromkeys(res))

def normalize_title(t):
    if not t:
        return ''
    t = re.sub(r'[^A-Za-z0-9\s]', ' ', str(t)).upper()
    return ' '.join(t.split())

def parse_seating_coursename(cname):
    if not cname:
        return {'original': '', 'title': '', 'norm_title': '', 'codes': [], 'normalized_codes': [], 'syllabus_year': '', 'syllabus_raw': ''}
    
    syl = ''
    m_syl = re.search(r'\[(.*?)\]', cname)
    if m_syl:
        syl = m_syl.group(1)
        cname_clean = cname[:m_syl.start()].strip()
    else:
        cname_clean = cname.strip()
    
    m_dash = re.match(r'^([A-Za-z0-9\(\)\s\.\/]+?)\s*-\s*(.+)$', cname_clean)
    codes = []
    title = ''
    if m_dash:
        code_part = m_dash.group(1).strip()
        title = m_dash.group(2).strip()
        codes.append(code_part)
    else:
        par_matches = list(re.finditer(r'\((.*?)\)', cname_clean))
        if par_matches:
            for pm in par_matches:
                codes.append(pm.group(1).strip())
            title = cname_clean[:par_matches[0].start()].strip()
        else:
            title = cname_clean
            
    m_yr = re.search(r'(20\d\d)', syl)
    syl_year = m_yr.group(1) if m_yr else ''
    
    norm_codes = []
    for c in codes:
        norm_codes.extend(extract_all_codes(c))
    
    return {
        'original': cname,
        'title': title,
        'norm_title': normalize_title(title),
        'codes': codes,
        'normalized_codes': list(dict.fromkeys(norm_codes)),
        'syllabus_year': syl_year,
        'syllabus_raw': syl
    }

def score_qp_to_seating(room_entry, qp):
    room_parsed = parse_seating_coursename(room_entry.get('courseName', ''))
    room_stream = room_entry.get('stream', 'Regular')
    
    score = 0
    reasons = []
    
    # 1. Course Code matching
    qp_codes = []
    if qp.get('course_code'):
        qp_codes.extend(extract_all_codes(qp['course_code']))
    if qp.get('filename_data', {}).get('course_code'):
        qp_codes.extend(extract_all_codes(qp['filename_data']['course_code']))
    qp_codes = list(dict.fromkeys(qp_codes))
    
    code_matched = False
    for rc in room_parsed['normalized_codes']:
        for qc in qp_codes:
            if rc == qc:
                score += 60
                code_matched = True
                reasons.append(f"Exact code match ({rc})")
                break
            elif len(rc) >= 5 and len(qc) >= 5 and (rc in qc or qc in rc):
                score += 45
                code_matched = True
                reasons.append(f"Partial code match ({rc} ~ {qc})")
                break
        if code_matched:
            break
            
    # 2. Title matching
    room_title = room_parsed['norm_title']
    qp_titles = []
    if qp.get('course_name'):
        qp_titles.append(normalize_title(qp['course_name']))
    if qp.get('filename_data', {}).get('course_name'):
        qp_titles.append(normalize_title(qp['filename_data']['course_name']))
        
    for qt in qp_titles:
        if not qt or not room_title:
            continue
        if room_title == qt:
            score += 35
            reasons.append("Exact title match")
            break
        elif (len(room_title) > 5 and len(qt) > 5) and (room_title in qt or qt in room_title):
            score += 25
            reasons.append(f"Substring title match ('{room_title}' in '{qt}')")
            break
        else:
            r_words = set(w for w in room_title.split() if len(w) > 3)
            q_words = set(w for w in qt.split() if len(w) > 3)
            if r_words and q_words:
                common = r_words.intersection(q_words)
                if common:
                    overlap_ratio = len(common) / min(len(r_words), len(q_words))
                    if overlap_ratio >= 0.5:
                        pts = int(20 * overlap_ratio)
                        score += pts
                        reasons.append(f"Title token overlap ({pts} pts: {common})")
                        break
                        
    # 3. Stream match
    stream = (room_stream or '').upper()
    qp_code = (qp.get('qp_code') or '').upper()
    qp_stream = (qp.get('exam_type') or qp.get('filename_data', {}).get('exam_type') or '').upper()
    
    is_sde_room = 'SDE' in stream or 'EDE' in stream
    is_sde_qp = qp_code.endswith('A') or 'SDE' in qp_stream or 'EDE' in qp_stream
    
    if is_sde_room and is_sde_qp:
        score += 15
        reasons.append("Stream match (SDE/EDE)")
    elif not is_sde_room and not is_sde_qp:
        score += 10
        reasons.append("Stream match (Regular)")
    elif is_sde_room and not is_sde_qp:
        score -= 15
        reasons.append("Stream mismatch (Room is SDE/EDE, QP is Regular)")
    elif not is_sde_room and is_sde_qp:
        score -= 30
        reasons.append("Stream mismatch (Room is Regular, QP is SDE)")

    # 4. Syllabus Year match
    r_syl = room_parsed.get('syllabus_year')
    q_syl = qp.get('syllabus_year')
    if r_syl and q_syl:
        if r_syl in q_syl:
            score += 10
            reasons.append(f"Syllabus match ({r_syl})")
        else:
            score -= 15
            reasons.append(f"Syllabus mismatch ({r_syl} vs {q_syl})")

    # 5. Date & Time match
    r_date = room_entry.get('date')
    q_date = qp.get('exam_date') or qp.get('filename_data', {}).get('exam_date')
    if r_date and q_date:
        # Normalize date format e.g. 10.07.26 vs 10.07.2026
        r_d_clean = re.sub(r'20(\d\d)', r'\1', r_date)
        q_d_clean = re.sub(r'20(\d\d)', r'\1', q_date)
        if r_d_clean == q_d_clean:
            score += 10
            reasons.append(f"Date match ({r_date})")

    status = "UNMATCHED"
    if score >= 80:
        status = "EXACT"
    elif score >= 50:
        status = "PARTIAL"

    return score, status, reasons

def match_all_rooms(seating_entries, qp_records):
    results = []
    qp_distribution = {}
    
    for r in seating_entries:
        best_score = -999
        best_qp = None
        best_status = "UNMATCHED"
        best_reasons = []
        
        for qp in qp_records:
            score, status, reasons = score_qp_to_seating(r, qp)
            if score > best_score:
                best_score = score
                best_qp = qp
                best_status = status
                best_reasons = reasons
                
        entry_copy = dict(r)
        if best_status in ("EXACT", "PARTIAL") and best_qp:
            matched_code = best_qp.get('qp_code', '')
            entry_copy['qpCode'] = matched_code
            entry_copy['matchedQpCode'] = matched_code
            entry_copy['matchedPdfFilename'] = best_qp.get('filename', '')
            entry_copy['matchedCourseCode'] = best_qp.get('course_code', '')
            entry_copy['matchedCourseName'] = best_qp.get('course_name', '')
            entry_copy['matchStatus'] = best_status
            entry_copy['matchScore'] = best_score
            entry_copy['matchReasons'] = best_reasons
            
            # Aggregate for QP distribution
            if matched_code not in qp_distribution:
                qp_distribution[matched_code] = {
                    'qpCode': matched_code,
                    'courseCode': best_qp.get('course_code', ''),
                    'courseName': best_qp.get('course_name', ''),
                    'pdfFilename': best_qp.get('filename', ''),
                    'totalRequired': 0,
                    'totalScribes': 0,
                    'rooms': []
                }
            dist = qp_distribution[matched_code]
            dist['totalRequired'] += int(entry_copy.get('count', 0))
            dist['totalScribes'] += int(entry_copy.get('scribeCount', 0))
            dist['rooms'].append({
                'roomSerial': entry_copy.get('roomSerial'),
                'stream': entry_copy.get('stream'),
                'count': entry_copy.get('count', 0),
                'scribeCount': entry_copy.get('scribeCount', 0)
            })
        else:
            entry_copy['matchedQpCode'] = None
            entry_copy['matchedPdfFilename'] = None
            entry_copy['matchedCourseCode'] = None
            entry_copy['matchedCourseName'] = None
            entry_copy['matchStatus'] = "UNMATCHED"
            entry_copy['matchScore'] = max(best_score, 0)
            entry_copy['matchReasons'] = best_reasons
            
        results.append(entry_copy)
        
    return results, list(qp_distribution.values())

print("=== TEST 1: 30.10.2026 SESSION (68 room entries) vs 18 PDFs in 30-10-26 ===")
with open(r'C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json', encoding='utf-8') as f:
    rooms_30 = json.load(f)

folder_30 = r'C:\Users\sures\Downloads\30-10-26'
pdf_records_30 = [extract_qp_data(p) for p in sorted(glob.glob(os.path.join(folder_30, '*.pdf')))]

matched_30, dist_30 = match_all_rooms(rooms_30, pdf_records_30)
exact_cnt = sum(1 for m in matched_30 if m['matchStatus'] == 'EXACT')
part_cnt = sum(1 for m in matched_30 if m['matchStatus'] == 'PARTIAL')
unmatched_cnt = sum(1 for m in matched_30 if m['matchStatus'] == 'UNMATCHED')

print(f"Results: {exact_cnt} EXACT, {part_cnt} PARTIAL, {unmatched_cnt} UNMATCHED")
print(f"Total Unique QPs Matched: {len(dist_30)}")
for d in dist_30:
    print(f"  QP: {d['qpCode']} | Course: {d['courseCode']} - {d['courseName'][:30]} | Total Copies Needed: {d['totalRequired']} across {len(d['rooms'])} rooms")

print("\n=== TEST 2: Room_Wise_Seating_Summary_2026-11-01 (1).json (64 entries) vs Downloads\\QP (45 PDFs) ===")
with open(r'C:\Users\sures\Downloads\Room_Wise_Seating_Summary_2026-11-01 (1).json', encoding='utf-8') as f:
    rooms_nov1 = json.load(f)

folder_qp = r'C:\Users\sures\Downloads\QP'
pdf_records_qp = [extract_qp_data(p) for p in sorted(glob.glob(os.path.join(folder_qp, '*.pdf')))]

matched_nov1, dist_nov1 = match_all_rooms(rooms_nov1, pdf_records_qp)
exact_cnt = sum(1 for m in matched_nov1 if m['matchStatus'] == 'EXACT')
part_cnt = sum(1 for m in matched_nov1 if m['matchStatus'] == 'PARTIAL')
unmatched_cnt = sum(1 for m in matched_nov1 if m['matchStatus'] == 'UNMATCHED')

print(f"Results: {exact_cnt} EXACT, {part_cnt} PARTIAL, {unmatched_cnt} UNMATCHED out of {len(rooms_nov1)} rooms")
print(f"Total Unique QPs Matched: {len(dist_nov1)}")
for d in dist_nov1[:10]:
    print(f"  QP: {d['qpCode']} | Course: {d['courseCode']} - {d['courseName'][:30]} | Total Copies: {d['totalRequired']} across {len(d['rooms'])} rooms")
