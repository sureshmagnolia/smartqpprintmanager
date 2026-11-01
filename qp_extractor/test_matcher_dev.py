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
    # Split on /, ,, &, and, or
    parts = re.split(r'[\/,\&]|\s+and\s+|\s+or\s+', str(text), flags=re.IGNORECASE)
    res = []
    for p in parts:
        nc = normalize_code(p)
        if nc and len(nc) >= 3:
            res.append(nc)
            # Also add stripped version without trailing number in parens e.g. COM5EJ3053 -> COM5EJ305
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
    syl = ''
    m_syl = re.search(r'\[(.*?)\]', cname)
    if m_syl:
        syl = m_syl.group(1)
        cname_clean = cname[:m_syl.start()].strip()
    else:
        cname_clean = cname.strip()
    
    # Check for 'CODE - Title' format
    m_dash = re.match(r'^([A-Za-z0-9\(\)\s\.\/]+?)\s*-\s*(.+)$', cname_clean)
    codes = []
    title = ''
    if m_dash:
        code_part = m_dash.group(1).strip()
        title = m_dash.group(2).strip()
        codes.append(code_part)
    else:
        # Check for 'TITLE (CODE) [(CODE2)]' format
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

def score_match(room_parsed, room_stream, qp):
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
        
    title_matched = False
    for qt in qp_titles:
        if not qt or not room_title:
            continue
        if room_title == qt:
            score += 35
            title_matched = True
            reasons.append("Exact title match")
            break
        elif (len(room_title) > 5 and len(qt) > 5) and (room_title in qt or qt in room_title):
            score += 25
            title_matched = True
            reasons.append(f"Substring title match ('{room_title}' in '{qt}')")
            break
        else:
            # Word token overlap
            r_words = set(w for w in room_title.split() if len(w) > 3)
            q_words = set(w for w in qt.split() if len(w) > 3)
            if r_words and q_words:
                common = r_words.intersection(q_words)
                if common:
                    overlap_ratio = len(common) / min(len(r_words), len(q_words))
                    if overlap_ratio >= 0.5:
                        pts = int(20 * overlap_ratio)
                        score += pts
                        title_matched = True
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

    return score, reasons

print("=== TESTING MATCH ON 30-10-26 PDFS AGAINST 30.10.2026 SESSION ===")
with open(r'C:\Users\sures\Downloads\ExamFlow_Full_Backup_2026-10-31.json', encoding='utf-8') as f:
    backup_data = json.load(f)

allotment = json.loads(backup_data.get('examRoomAllotment', '{}'))
sess = '30.10.2026 | 02:00 PM'
rooms_30 = allotment.get(sess, [])

room_entries_30 = []
for r_idx, r in enumerate(rooms_30, 1):
    stream = r.get('stream', 'Regular')
    course_counts = {}
    for s in r.get('students', []):
        c = s.get('Course', '')
        course_counts[c] = course_counts.get(c, 0) + 1
    for c, cnt in course_counts.items():
        room_entries_30.append({
            'roomSerial': r_idx,
            'stream': stream,
            'courseName': c,
            'count': cnt
        })

print(f"Total room entries for {sess}: {len(room_entries_30)}")

folder_30 = r'C:\Users\sures\Downloads\30-10-26'
pdf_files = sorted(glob.glob(os.path.join(folder_30, '*.pdf')))
pdf_records = [extract_qp_data(p) for p in pdf_files]

print(f"Loaded {len(pdf_records)} PDFs from {folder_30}")

matched_count = 0
for rentry in room_entries_30:
    r_parsed = parse_seating_coursename(rentry['courseName'])
    best_score = -999
    best_qp = None
    best_reasons = []
    
    for qp in pdf_records:
        score, reasons = score_match(r_parsed, rentry['stream'], qp)
        if score > best_score:
            best_score = score
            best_qp = qp
            best_reasons = reasons
            
    if best_score >= 50:
        matched_count += 1
        qp_code = best_qp['qp_code']
        pdf_fn = best_qp['filename']
        print(f"Room {rentry['roomSerial']} ({rentry['stream']}) -> Matched QP {qp_code} (Score: {best_score})")
        print(f"   Course: {rentry['courseName'][:55]}")
        print(f"   PDF: {pdf_fn}")
    else:
        print(f"Room {rentry['roomSerial']} ({rentry['stream']}) -> NO MATCH (Best score: {best_score})")
        print(f"   Course: {rentry['courseName']}")

print(f"\nSummary: {matched_count} / {len(room_entries_30)} matched")
