import fitz
import glob
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

folder = r'C:\Users\sures\Downloads\30-10-26'
pdfs = sorted(glob.glob(os.path.join(folder, '*.pdf')))

def js_extract_sim(text, filename):
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    
    # 1. QP Code
    qp_code = None
    for l in lines[:15]:
        m = re.search(r'(?:Q\.?P\.?\s*(?:Code)?\s*[:\-\s]*)?([A-Z])\s*(\d{5,7})([A-Za-z])?\b', l, re.I)
        if m:
            sfx = m.group(3).upper() if m.group(3) else ''
            qp_code = f'{m.group(1).upper()} {m.group(2)}{sfx}'
            break
    if not qp_code:
        m = re.search(r'(?:^|[_\-\s])([A-Za-z])?(\d{5,6})([A-Za-z])?(?:[_\-\s]|$)', filename)
        if m:
            letter = m.group(1).upper() if m.group(1) else 'D'
            digits = m.group(2)
            suffix = m.group(3).upper() if m.group(3) else ''
            qp_code = f'{letter} {digits}{suffix}'
            
    # 2. Syllabus Year
    syl_year = None
    for l in lines:
        m = re.search(r'(?:(20\d\d)\s*[-–—]\s*(20\d\d)|(?:Syllabus\s*Year\s*[:\-]?\s*|(?:^|[^\d])\(?)(20\d\d)(?:\s*Syllabus|\s*Admissions?\)?))', l, re.I)
        if m:
            syl_year = f'{m.group(1)}-{m.group(2)}' if m.group(1) else m.group(3)
            break
    if not syl_year:
        for l in lines:
            m = re.search(r'\b(201\d|202\d)\b', l)
            if m and 'EXAMINATION' not in l.upper():
                syl_year = m.group(1)
                break
            
    # 3. Course Code & Title
    course_code = None
    course_name = None
    
    for i, l in enumerate(lines[:20]):
        if any(ign in l.upper() for ign in ['DEGREE EXAMINATION', 'NOVEMBER', 'PAGES', 'REGISTER', 'TURN OVER', 'TIME :', 'MAXIMUM', 'NAME']):
            continue
            
        # Pattern A: Code followed by dash or 2+ spaces or space-then-capital / indic char
        m = re.match(r'^([A-Z]{2,4}\s*\d\s*[A-Z0-9\(\)\/\.\s\-–—]+?)(?:\s*[-–—\xad]+\s*|\s{2,}|\s+(?=[A-Z\u0D00-\u0D7F\u0B80-\u0BFF]))(.+)$', l)
        if m and re.search(r'[A-Z]{2,4}\s*\d', m.group(1)):
            c_cand = m.group(1).strip()
            t_cand = m.group(2).strip()
            if len(c_cand) <= 30 and len(t_cand) >= 3 and not re.search(r'^(Section|Part|Time|Marks)', t_cand, re.I):
                course_code = c_cand
                course_name = t_cand
                break
                
        # Pattern B: Code on line i, Title on line i+1 (e.g. MAT5EJ301(1) \n Mathematical Foundations...)
        m_code_only = re.match(r'^([A-Z]{2,4}\s*\d\s*[A-Z0-9\(\)\/\.\s]+)$', l)
        if m_code_only and i + 1 < len(lines):
            next_l = lines[i+1].strip()
            if len(next_l) >= 4 and not any(ign in next_l.upper() for ign in ['ADMISSION', 'SEMESTER', 'EXAMINATION', 'MAXIMUM', 'TIME', 'SECTION']):
                course_code = m_code_only.group(1).strip()
                course_name = next_l
                break

    return qp_code, course_code, course_name, syl_year

for p in pdfs:
    fname = os.path.basename(p)
    doc = fitz.open(p)
    txt = doc[0].get_text() if len(doc) > 0 else ''
    qp, code, title, yr = js_extract_sim(txt, fname)
    print(f'{fname} -> QP: {qp} | Code: {code} | Title: {title} | Yr: {yr}')

