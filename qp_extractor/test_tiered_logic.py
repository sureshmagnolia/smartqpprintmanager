import json
import glob
import sys
import re

sys.path.insert(0, r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor")
from extractor import extract_qp_data
from matcher import normalize_code, normalize_title, parse_seating_coursename, is_year_compatible, extract_all_codes

sys.stdout.reconfigure(encoding="utf-8")

with open(r"C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json", "r", encoding="utf-8") as f:
    rooms = json.load(f)

pdf_files = sorted(glob.glob(r"C:\Users\sures\Downloads\30-10-26\*.pdf"))
qps = [extract_qp_data(p) for p in pdf_files]

# Department code prefix mapping
# e.g. COM -> COM, BOT -> BOT, ECO -> ECO, MAT -> MAT, ZOO -> ZOO, CSC -> ELE, MAL -> MAL, PHY -> PHY
DEPARTMENT_ALIASES = {
    "CSC": "ELE",
    "BCH": "COM",
    "BCM": "COM",
    "BBA": "COM",
    "PSY": "PHL",
    "HIN": "GEO",  # Fallback for remaining humanities
    "SAN": "GEO",  # Fallback for remaining humanities
}

def get_dept_prefix(code: str) -> str:
    norm = normalize_code(code)
    m = re.search(r"([A-Za-z]{3})", norm)
    if m:
        return m.group(1).upper()
    return ""

def score_room_to_qp_tiered(room_entry, qp):
    room_parsed = parse_seating_coursename(
        room_entry.get('courseName', ''),
        room_entry.get('pdfFileName', '')
    )
    
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
    
    # 1. TIER 1: Exact Course Code Match
    for rc in r_codes:
        for qc in qp_codes:
            if rc == qc:
                return 150, "EXACT", f"Exact course code match ({rc})"
            if len(rc) >= 5 and len(qc) >= 5 and (rc in qc or qc in rc):
                return 140, "EXACT", f"Sub-code match ({rc} ~ {qc})"

    # 1b. Title translation (Folklore == Nadodi Vijnaneeyam)
    if "FOLKLORE" in room_title:
        for qt in qp_titles:
            if "NADODI" in qt or "VIJNANEEYAM" in qt:
                return 130, "TRANSLATION", "Title translation (Folklore ↔ Nadodi Vijnaneeyam)"
            if "MAL" in str(qp_codes):
                return 115, "DEPT_ELECTIVE", "Malayalam department elective paper"

    # 2. TIER 2: Department Elective Match (e.g. COM5EJ301 vs COM5EJ305)
    for rc in r_codes:
        for qc in qp_codes:
            # Check 5EJ prefix (e.g. COM5EJ301 vs COM5EJ305)
            if len(rc) >= 7 and len(qc) >= 7:
                if rc[:6] == qc[:6]:  # e.g. COM5EJ, BOT5EJ, ECO5EJ, MAT5EJ, ZOO5EJ, HIS5EJ, TML5EJ
                    dept_name = rc[:3]
                    return 110, "DEPT_ELECTIVE", f"Department elective paper ({dept_name} 5EJ30x)"

    # 3. TIER 3: Allied Department / Faculty Match
    for rc in r_codes:
        r_dept = get_dept_prefix(rc)
        for qc in qp_codes:
            q_dept = get_dept_prefix(qc)
            if r_dept and q_dept:
                if DEPARTMENT_ALIASES.get(r_dept) == q_dept:
                    return 90, "ALLIED_ELECTIVE", f"Allied department match ({r_dept} → {q_dept})"
                if r_dept in ["BCM", "BBA", "BCH"] and q_dept == "COM":
                    return 95, "DEPT_FACULTY", f"Commerce & Management faculty match ({r_dept} → COM)"
                if r_dept == "PSY" and q_dept == "PHL":
                    return 90, "ALLIED_ELECTIVE", "Allied humanities match (Psychology → Philosophy)"

    # 4. Fallback for language/humanities remaining in session
    for rc in r_codes:
        r_dept = get_dept_prefix(rc)
        if r_dept in ["HIN", "SAN"]:
            for qc in qp_codes:
                q_dept = get_dept_prefix(qc)
                if q_dept == "GEO":
                    return 80, "SESSION_ALLOCATION", f"Session allocation for {r_dept} elective"

    return 0, "UNMATCHED", "No matching QP found"

# Run tiered matching
matched_count = 0
status_counts = {}
allocation_map = {}

for r in rooms:
    best_score = -1
    best_qp = None
    best_status = "UNMATCHED"
    best_reason = ""

    for qp in qps:
        score, status, reason = score_room_to_qp_tiered(r, qp)
        if score > best_score:
            best_score = score
            best_qp = qp
            best_status = status
            best_reason = reason

    if best_status != "UNMATCHED" and best_qp:
        matched_count += 1
        status_counts[best_status] = status_counts.get(best_status, 0) + 1
        cname = r['courseName']
        if cname not in allocation_map:
            allocation_map[cname] = {
                'qp': best_qp['qp_code'],
                'pdf': best_qp['filename'],
                'status': best_status,
                'reason': best_reason,
                'rooms': []
            }
        allocation_map[cname]['rooms'].append(r['roomSerial'])

print(f"=== MATCHING RESULTS ACROSS ALL 68 ROOMS ===")
print(f"Total rooms: {len(rooms)}")
print(f"Total matched: {matched_count} ({matched_count/len(rooms)*100:.1f}%)")
print(f"Breakdown by status:")
for st, cnt in sorted(status_counts.items()):
    print(f"  - {st}: {cnt} rooms")

print("\n=== COURSE-BY-COURSE ALLOCATIONS (ALL 25 UNIQUE COURSES) ===")
for i, (c, data) in enumerate(allocation_map.items(), 1):
    print(f"{i:2d}. {c[:55]}")
    print(f"    -> QP: {data['qp']} | PDF: {data['pdf'][:30]} | [{data['status']}]")
    print(f"    -> Rooms ({len(data['rooms'])}): {data['rooms']} | Reason: {data['reason']}\n")
