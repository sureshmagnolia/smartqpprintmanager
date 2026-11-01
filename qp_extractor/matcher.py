"""
Calicut University Question Paper & Room Seating Matcher
=========================================================
Implements the 3-pillar matching engine:
  1. Course Code (spaceless comparison, e.g. 'TML5B10', 'PHY5EJ301', 'MTS5B09')
  2. Course Title (normalized string & token overlap, e.g. 'PUTHUMURAI ILAKKIYAVIYAL')
  3. Syllabus Year (exact year or Calicut University cycle, e.g. '2019', '2024')

Cross-referenced against both 'courseName' and 'pdfFileName' (__ segment).
Strictly prevents false matches between different elective variants (e.g. COM5EJ301 != COM5EJ305).
"""

import os
import re
import json
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple


def normalize_code(c: Any) -> str:
    """Removes all whitespace, hyphens, and non-alphanumeric characters except () and /."""
    if not c:
        return ""
    s = str(c).upper().strip()
    return re.sub(r'[\s\-_]+', '', s)


def extract_all_codes(text: Any) -> List[str]:
    """
    Extracts all possible normalized course code variants from a string.
    Handles compound codes like 'PHY5EJ301(1)/ APH5EJ303(2)' and 'MAL3E (3)'.
    Also handles OCR/data entry substitution between letter 'O' and digit '0' (e.g. PSY5BO5 <-> PSY5B05).
    """
    if not text:
        return []
    s = str(text).strip()
    
    # Split on slash for compound codes
    segments = [seg.strip() for seg in re.split(r'[/|]', s) if seg.strip()]
    
    codes = []
    for seg in segments:
        clean = re.sub(r'[\s\-_]+', '', seg).upper()
        if clean:
            codes.append(clean)
            # Without parentheses content: e.g. PHY5EJ301(1) -> PHY5EJ301
            without_parens = re.sub(r'\(.*?\)', '', clean)
            if without_parens and without_parens != clean:
                codes.append(without_parens)
            # Unparenthesized: e.g. PHY5EJ301(1) -> PHY5EJ3011
            unparens = clean.replace('(', '').replace(')', '')
            if unparens and unparens != clean:
                codes.append(unparens)

    # Add O <-> 0 digit substitutions (e.g. PSY5BO5 <-> PSY5B05)
    expanded = list(codes)
    for c in codes:
        m = re.match(r'^([A-Z]{3,4}\d[A-Z])O(\d+)$', c)
        if m:
            expanded.append(f"{m.group(1)}0{m.group(2)}")
        m2 = re.match(r'^([A-Z]{3,4}\d[A-Z])0(\d+)$', c)
        if m2:
            expanded.append(f"{m2.group(1)}O{m2.group(2)}")

    return list(dict.fromkeys(expanded))


def normalize_title(t: Any) -> str:
    """Normalizes a course title by removing punctuation, extra spaces, and uppercasing."""
    if not t:
        return ""
    s = str(t).upper().strip()
    s = re.sub(r'[\-\u2010-\u2015\u2212\xad_:\,\.\(\)\[\]\/\'\"\`]+', ' ', s)
    s = re.sub(r'\s+', ' ', s)
    return s.strip()


def parse_seating_coursename(cname: str, pdf_file_name: str = "") -> Dict[str, Any]:
    """
    Parses course details from ExamFlow seating JSON.
    Inspects BOTH 'courseName' and 'pdfFileName' (the segment after '__').
    Extracts:
      - Course Title
      - Course Codes (with spaceless expansions)
      - Syllabus Year
    """
    # 1. Candidate sources
    sources = []
    if cname:
        sources.append(str(cname).strip())
    if pdf_file_name:
        fn_str = str(pdf_file_name).strip()
        if '__' in fn_str:
            meta_seg = fn_str.split('__', 1)[1]
            meta_clean = re.sub(r'\.pdf$', '', meta_seg, flags=re.I).strip()
            sources.append(meta_clean)
        else:
            meta_clean = re.sub(r'\.pdf$', '', fn_str, flags=re.I).strip()
            sources.append(meta_clean)

    title = ""
    codes: List[str] = []
    syl_year = ""
    syl_raw = ""

    for src in sources:
        # Extract [Syllabus]
        m_syl = re.search(r'\[(.*?)\]', src)
        if m_syl:
            syl_raw = m_syl.group(1).strip()
            m_yr = re.search(r'(20\d\d)', syl_raw)
            if m_yr and not syl_year:
                syl_year = m_yr.group(1)
            src_clean = src[:m_syl.start()].strip()
        else:
            src_clean = src

        # Format A: CODE - Title (e.g. 'COM5EJ301(1) - Management of Financial Services')
        m_code_dash = re.match(r'^([A-Za-z]{2,}\d[A-Za-z0-9\(\)\/\s\.]*|\d+\.\d+\s*[A-Za-z]+)\s*[\-\u2010-\u2015\u2212\xad]+\s*(.+)$', src_clean)
        if m_code_dash:
            code_cand = m_code_dash.group(1).strip()
            title_cand = m_code_dash.group(2).strip()
            if code_cand not in codes:
                codes.append(code_cand)
            if not title:
                title = title_cand

        # Format B: Title (CODE) [Syllabus] (e.g. 'PUTHUMURAI ILAKKIYAVIYAL (TML5B10)')
        par_matches = list(re.finditer(r'\((.*?)\)', src_clean))
        if par_matches:
            title_end = None
            for pm in par_matches:
                inner = pm.group(1).strip()
                if re.search(r'[A-Za-z]', inner) and re.search(r'\d', inner):
                    if inner not in codes:
                        codes.append(inner)
                    if title_end is None:
                        title_end = pm.start()
                elif not syl_year and re.match(r'^20\d\d$', inner):
                    syl_year = inner
            if not title and title_end is not None:
                title = src_clean[:title_end].strip()

        # Format C: Leading code with space (e.g. 'BCM 5B 11 FINANCIAL MANAGEMENT')
        if not codes:
            m_lead = re.match(r'^([A-Za-z]{2,4}\s*\d[A-Za-z0-9\s]{1,8}?)\s+([A-Za-z]{3,}.*)$', src_clean)
            if m_lead and re.search(r'[A-Za-z]', m_lead.group(1)) and re.search(r'\d', m_lead.group(1)):
                codes.append(m_lead.group(1).strip())
                if not title:
                    title = m_lead.group(2).strip()

        # Format D: Fallback title
        if not title:
            title = src_clean

    title = re.sub(r'[\-\u2010-\u2015\u2212\xad:\,\.\s]+$', '', title).strip()

    norm_codes = []
    for c in codes:
        norm_codes.extend(extract_all_codes(c))

    return {
        'original': cname or pdf_file_name,
        'title': title,
        'norm_title': normalize_title(title),
        'codes': codes,
        'normalized_codes': list(dict.fromkeys(norm_codes)),
        'syllabus_year': syl_year,
        'syllabus_raw': syl_raw
    }


def is_year_compatible(seat_year: Optional[str], qp_year: Optional[str]) -> Tuple[bool, str]:
    """Checks whether syllabus years are compatible across Calicut University cycles."""
    if not seat_year or not qp_year:
        return True, "Year neutral (unspecified)"
    sy = str(seat_year).strip()
    qy = str(qp_year).strip()
    if sy == qy or sy in qy:
        return True, f"Exact syllabus match ({sy})"
    if sy == '2020' and any(y in qy for y in ['2020', '2021', '2022', '2023']):
        return True, f"Syllabus cycle match ({sy} ~ {qy})"
    if sy == '2019' and any(y in qy for y in ['2019', '2020', '2021', '2022', '2023']):
        return True, f"Syllabus cycle match ({sy} ~ {qy})"
    if sy == '2024' and '2024' in qy:
        return True, "Exact 2024 FYUGP syllabus match"
    return False, f"Syllabus mismatch ({sy} vs {qy})"


DEPARTMENT_ALIASES = {
    "CSC": "ELE",
    "BCH": "COM",
    "BCM": "COM",
    "BBA": "COM",
    "PSY": "PHL",
    "HIN": "GEO",
    "SAN": "GEO",
}

def get_dept_prefix(code: str) -> str:
    norm = normalize_code(code)
    m = re.search(r"([A-Za-z]{3})", norm)
    if m:
        return m.group(1).upper()
    return ""


def score_qp_to_seating(
    room_entry: Dict[str, Any], 
    qp: Dict[str, Any],
    mode: str = "strict"
) -> Tuple[int, str, List[str]]:
    """
    Evaluates compatibility between a Room Seating entry and a Question Paper record.
    Supports two modes:
      - 'strict' (default): 3-Pillar verification requiring Course Code (spaceless) + Title + Year match.
        Different elective variants (e.g. MAT5EJ303 vs MAT5EJ301) and different course titles strictly DO NOT MATCH.
      - 'department': Multi-tiered matching that links elective papers by department basket when exact code is missing.
    """
    room_parsed = parse_seating_coursename(
        room_entry.get('courseName', ''), 
        room_entry.get('pdfFileName', '')
    )
    room_stream = room_entry.get('stream', 'Regular')

    # Extract all candidate codes & titles from QP
    qp_codes = []
    if qp.get('course_code'):
        qp_codes.extend(extract_all_codes(qp['course_code']))
    if qp.get('filename_data', {}).get('course_code'):
        qp_codes.extend(extract_all_codes(qp['filename_data']['course_code']))
    qp_codes = list(dict.fromkeys(qp_codes))

    qp_titles = []
    if qp.get('course_name'):
        qp_titles.append(normalize_title(qp['course_name']))
    if qp.get('filename_data', {}).get('course_name'):
        qp_titles.append(normalize_title(qp['filename_data']['course_name']))

    room_title = room_parsed['norm_title']
    r_codes = room_parsed['normalized_codes']

    if mode == "department":
        # TIER 1: Exact Course Code Match
        for rc in r_codes:
            for qc in qp_codes:
                if rc == qc:
                    return 150, "EXACT", [f"Exact course code match ({rc})"]
                if len(rc) >= 5 and len(qc) >= 5 and (rc in qc or qc in rc):
                    return 140, "EXACT", [f"Sub-code match ({rc} ~ {qc})"]

        # TIER 1b: Title translation (e.g. Folklore == Nadodi Vijnaneeyam)
        if "FOLKLORE" in room_title:
            for qt in qp_titles:
                if "NADODI" in qt or "VIJNANEEYAM" in qt:
                    return 130, "TRANSLATION", ["Title translation (Folklore ↔ Nadodi Vijnaneeyam)"]
                if "MAL" in str(qp_codes):
                    return 115, "DEPT_ELECTIVE", ["Malayalam department elective paper"]

        # TIER 2: Department Elective Paper (e.g. COM5EJ301 vs COM5EJ305, BOT5EJ301 vs BOT5EJ302)
        for rc in r_codes:
            for qc in qp_codes:
                if len(rc) >= 7 and len(qc) >= 7 and rc[:6] == qc[:6]:
                    dept_name = rc[:3]
                    return 110, "DEPT_ELECTIVE", [f"Department elective match ({dept_name} 5EJ30x)"]

        # TIER 3: Allied Department / Faculty Match
        for rc in r_codes:
            r_dept = get_dept_prefix(rc)
            for qc in qp_codes:
                q_dept = get_dept_prefix(qc)
                if r_dept and q_dept:
                    if DEPARTMENT_ALIASES.get(r_dept) == q_dept:
                        return 95, "ALLIED_ELECTIVE", [f"Allied department match ({r_dept} → {q_dept})"]
                    if r_dept in ["BCM", "BBA", "BCH"] and q_dept == "COM":
                        return 95, "ALLIED_ELECTIVE", [f"Commerce & Management faculty match ({r_dept} → COM)"]
                    if r_dept == "PSY" and q_dept == "PHL":
                        return 90, "ALLIED_ELECTIVE", ["Allied humanities match (Psychology → Philosophy)"]

        # TIER 4: Session Allocation for remaining language electives
        for rc in r_codes:
            r_dept = get_dept_prefix(rc)
            if r_dept in ["HIN", "SAN"]:
                for qc in qp_codes:
                    q_dept = get_dept_prefix(qc)
                    if q_dept == "GEO":
                        return 80, "ALLIED_ELECTIVE", [f"Session allocation for {r_dept} elective"]

        return 0, "UNMATCHED", ["No matching QP found in session batch"]

    # --- Mode: 'strict' ---
    score = 0
    reasons = []
    code_matched = False
    different_elective = False

    for rc in r_codes:
        for qc in qp_codes:
            if rc == qc:
                score += 70
                code_matched = True
                reasons.append(f"Exact spaceless code match ({rc})")
                break
            elif len(rc) >= 5 and len(qc) >= 5 and (rc in qc or qc in rc):
                score += 60
                code_matched = True
                reasons.append(f"Sub-code match ({rc} ~ {qc})")
                break
            elif len(rc) >= 7 and len(qc) >= 7 and rc[:6] == qc[:6] and rc != qc:
                different_elective = True
                reasons.append(f"Different elective paper in same department ({rc} vs {qc})")
        if code_matched:
            break

    title_matched = False
    title_mismatch = False

    for qt in qp_titles:
        if not qt or not room_title:
            continue
        if room_title == qt:
            score += 40
            title_matched = True
            reasons.append(f"Exact title match ('{room_title}')")
            break
        elif (len(room_title) > 6 and len(qt) > 6) and (room_title in qt or qt in room_title):
            score += 30
            title_matched = True
            reasons.append("Substring title match")
            break
        else:
            r_words = set(w for w in room_title.split() if len(w) > 3)
            q_words = set(w for w in qt.split() if len(w) > 3)
            if r_words and q_words:
                common = r_words.intersection(q_words)
                overlap_ratio = len(common) / min(len(r_words), len(q_words))
                if overlap_ratio >= 0.5:
                    pts = int(30 * overlap_ratio)
                    score += pts
                    title_matched = True
                    reasons.append(f"Title token overlap ({pts} pts: {common})")
                    break

    # Check if QP has vernacular title in native script or belongs to a language department
    is_vernacular_title = any(
        bool(re.search(r'[\u0D00-\u0D7F\u0B80-\u0BFF\u0900-\u097F\u0600-\u06FF]', qt))
        for qt in qp_titles
    ) or any(
        rc.startswith(('MAL', 'TML', 'HIN', 'SKT', 'ARB', 'URD'))
        for rc in r_codes
    )

    if not title_matched and code_matched and is_vernacular_title:
        title_matched = True
        score += 35
        reasons.append("Vernacular title in native script for language course")

    if not title_matched and room_title and len(room_title) > 6:
        for qt in qp_titles:
            if qt and len(qt) > 6:
                if code_matched and is_vernacular_title:
                    continue
                r_words = set(w for w in room_title.split() if len(w) > 3)
                q_words = set(w for w in qt.split() if len(w) > 3)
                if r_words and q_words and not r_words.intersection(q_words):
                    title_mismatch = True
                    score -= 40
                    reasons.append(f"Subject conflict: '{room_title}' != '{qt}'")
                    break

    if different_elective and not title_matched:
        score -= 50

    stream = (room_stream or '').upper()
    qp_code_str = (qp.get('qp_code') or '').upper()
    qp_stream = (qp.get('exam_type') or qp.get('filename_data', {}).get('exam_type') or '').upper()

    is_sde_room = 'SDE' in stream or 'EDE' in stream
    is_sde_qp = qp_code_str.endswith('A') or 'SDE' in qp_stream or 'EDE' in qp_stream

    if is_sde_room and is_sde_qp:
        score += 10
        reasons.append("Stream match (SDE/EDE)")
    elif not is_sde_room and not is_sde_qp:
        score += 10
        reasons.append("Stream match (Regular)")
    elif is_sde_room and not is_sde_qp:
        score -= 25
        reasons.append("Stream mismatch (Room is SDE/EDE, QP is Regular)")
    elif not is_sde_room and is_sde_qp:
        score -= 30
        reasons.append("Stream mismatch (Room is Regular, QP is SDE)")

    r_syl = room_parsed.get('syllabus_year')
    q_syl = qp.get('syllabus_year')
    yr_compat, yr_reason = is_year_compatible(r_syl, q_syl)
    if yr_compat:
        score += 15
        reasons.append(yr_reason)
    else:
        score -= 35
        reasons.append(yr_reason)

    r_date = room_entry.get('date')
    q_date = qp.get('exam_date') or qp.get('filename_data', {}).get('exam_date')
    if r_date and q_date:
        r_d_clean = re.sub(r'20(\d\d)', r'\1', str(r_date))
        q_d_clean = re.sub(r'20(\d\d)', r'\1', str(q_date))
        if r_d_clean == q_d_clean:
            score += 10
            reasons.append(f"Date match ({r_date})")

    is_valid_match = False
    if not title_mismatch and not different_elective:
        if code_matched and yr_compat and score >= 80:
            is_valid_match = True
        elif title_matched and yr_compat and score >= 80:
            is_valid_match = True

    status = "EXACT" if is_valid_match else "UNMATCHED"
    return score, status, reasons


def match_all_rooms(
    seating_entries: List[Dict[str, Any]], 
    qp_records: List[Dict[str, Any]],
    mode: str = "strict"
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], Dict[str, Any]]:
    """
    Matches every room seating entry to the best question paper.
    Supports two modes:
      - 'strict' (default): 3-Pillar verification (Course Code + Title + Year). Only verified matches are assigned QP Code.
      - 'department': Multi-tiered matching that links elective papers by department basket when exact code is missing.
    Returns:
      (matched_entries, qp_distribution_list, summary_statistics)
    """
    matched_entries = []
    qp_distribution: Dict[str, Dict[str, Any]] = {}
    
    total_rooms = len(seating_entries)
    full_matches = 0
    dept_matches = 0
    trans_matches = 0
    allied_matches = 0
    unmatched_count = 0

    for r in seating_entries:
        room_parsed = parse_seating_coursename(
            r.get('courseName', ''),
            r.get('pdfFileName', '')
        )
        
        best_score = -999
        best_qp = None
        best_status = "UNMATCHED"
        best_reasons: List[str] = []

        for qp in qp_records:
            score, status, reasons = score_qp_to_seating(r, qp, mode=mode)
            if score > best_score:
                best_score = score
                best_qp = qp
                best_status = status
                best_reasons = reasons

        entry_copy = dict(r)

        is_matched = (best_status != "UNMATCHED") and (best_qp is not None)

        if is_matched:
            matched_code = best_qp.get('qp_code', '')
            entry_copy['qpCode'] = matched_code
            entry_copy['matchedQpCode'] = matched_code
            entry_copy['matchedPdfFilename'] = best_qp.get('filename', '')
            entry_copy['matchedCourseCode'] = best_qp.get('course_code', '')
            entry_copy['matchedCourseName'] = best_qp.get('course_name', '')
            entry_copy['matchStatus'] = best_status
            entry_copy['matchScore'] = best_score
            entry_copy['matchReasons'] = best_reasons

            if best_status == "EXACT":
                full_matches += 1
            elif best_status == "DEPT_ELECTIVE":
                dept_matches += 1
            elif best_status == "TRANSLATION":
                trans_matches += 1
            else:
                allied_matches += 1

            # Aggregate for QP distribution summary
            if matched_code not in qp_distribution:
                qp_distribution[matched_code] = {
                    'qpCode': matched_code,
                    'courseCode': best_qp.get('course_code', ''),
                    'courseName': best_qp.get('course_name', ''),
                    'pdfFilename': best_qp.get('filename', ''),
                    'matchStatus': best_status,
                    'totalRequired': 0,
                    'totalScribes': 0,
                    'rooms': []
                }
            dist = qp_distribution[matched_code]
            count = int(r.get('count', 0) or 0)
            scribes = int(r.get('scribeCount', 0) or 0)
            dist['totalRequired'] += count
            dist['totalScribes'] += scribes
            dist['rooms'].append({
                'roomSerial': r.get('roomSerial'),
                'roomName': f"Room {r.get('roomSerial')}",
                'count': count,
                'scribes': scribes,
                'stream': r.get('stream', '')
            })
        else:
            entry_copy['qpCode'] = ""
            entry_copy['matchedQpCode'] = ""
            entry_copy['matchedPdfFilename'] = ""
            entry_copy['matchStatus'] = "UNMATCHED"
            entry_copy['matchScore'] = best_score
            
            target_code = room_parsed['codes'][0] if room_parsed['codes'] else 'Unknown Code'
            target_title = room_parsed['title'] or 'Unknown Title'
            if best_reasons and any("Different elective" in reas or "conflict" in reas for reas in best_reasons):
                conflict_reason = next(reas for reas in best_reasons if "Different elective" in reas or "conflict" in reas)
                entry_copy['matchReasons'] = [f"Requires {target_code} — {target_title} (Mismatch: {conflict_reason})"]
            else:
                entry_copy['matchReasons'] = [f"QP Missing from batch (Requires {target_code} — {target_title})"]
            unmatched_count += 1

        matched_entries.append(entry_copy)

    # Convert distribution dict to list
    qp_dist_list = sorted(qp_distribution.values(), key=lambda x: str(x['qpCode']))
    for item in qp_dist_list:
        item['roomCount'] = len(item['rooms'])
        item['roomsSummary'] = ", ".join([f"{rm['roomName']} ({rm['count']})" for rm in item['rooms']])

    total_candidates = sum(int(r.get('count', 0) or 0) for r in seating_entries)
    total_scribes = sum(int(r.get('scribeCount', 0) or 0) for r in seating_entries)
    total_matched = full_matches + dept_matches + trans_matches + allied_matches

    stats = {
        'totalRooms': total_rooms,
        'total_entries': total_rooms,
        'totalCandidates': total_candidates,
        'totalScribes': total_scribes,
        'exactMatches': full_matches,
        'full_matches': full_matches,
        'deptElectiveMatches': dept_matches,
        'translationMatches': trans_matches,
        'alliedMatches': allied_matches,
        'partialMatches': dept_matches + trans_matches + allied_matches,
        'unmatchedEntries': unmatched_count,
        'unmatched': unmatched_count,
        'match_rate_pct': round((total_matched / total_rooms * 100), 1) if total_rooms else 0.0,
        'unique_qps_matched': len(qp_dist_list),
        'matchingMode': mode
    }

    return matched_entries, qp_dist_list, stats


def export_populated_seating_json(
    matched_entries: List[Dict[str, Any]], 
    output_path: Optional[str] = None
) -> str:
    """
    Exports the populated ExamFlow seating JSON where qpCode is filled.
    Preserves exact original structure expected by ExamFlow import.
    Returns the JSON string, and if output_path is provided, writes it to disk.
    """
    export_list = []
    for m in matched_entries:
        item = {
            "stream": m.get("stream", "Regular"),
            "date": m.get("date", ""),
            "time": m.get("time", ""),
            "roomSerial": m.get("roomSerial", 1),
            "qpCode": m.get("qpCode", ""),
            "courseName": m.get("courseName", ""),
            "count": m.get("count", 0),
            "scribeCount": m.get("scribeCount", 0),
            "pdfFileName": m.get("pdfFileName", "")
        }
        export_list.append(item)

    json_str = json.dumps(export_list, indent=2, ensure_ascii=False)
    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(json_str)

    return json_str
