import re

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
    
    # Extract codes in parentheses e.g. (BCM5B10), (MTH 3E01), (SKT 3E08)
    codes = []
    title = ''
    
    # 1. Check for 'CODE - Title' format where CODE has digits (e.g., COM5EJ301(1) - ..., 5.4 BCH - ...)
    m_code_dash = re.match(r'^([A-Za-z]{2,}\d[A-Za-z0-9\(\)\/\s\.]*|\d+\.\d+\s*[A-Za-z]+)\s*[-—–]\s*(.+)$', cname_clean)
    if m_code_dash:
        codes.append(m_code_dash.group(1).strip())
        title = m_code_dash.group(2).strip()
    else:
        # Check for parentheses containing course codes e.g. TITLE (CODE1) (CODE2)
        # Note: (1) or (2) alone is not a course code, it's an option/paper number unless preceded by code
        par_matches = list(re.finditer(r'\((.*?)\)', cname_clean))
        if par_matches:
            title_end = None
            for pm in par_matches:
                inner = pm.group(1).strip()
                # If inner has digits and letters (e.g. BCM5B10, MTH 3E01, 5.4 BCH)
                if re.search(r'[A-Za-z]', inner) and re.search(r'\d', inner):
                    codes.append(inner)
                    if title_end is None:
                        title_end = pm.start()
            if title_end is not None:
                title = cname_clean[:title_end].strip()
            else:
                title = cname_clean
        else:
            title = cname_clean

    m_yr = re.search(r'(20\d\d)', syl)
    syl_year = m_yr.group(1) if m_yr else ''
    
    return {
        'original': cname,
        'title': title,
        'codes': codes,
        'syllabus_year': syl_year
    }

test_cases = [
    'CO-OPERATIVE THEORY AND PRACTICE (BCM5B10) [2019 SYLLABUS]',
    'FINANCIAL MARKETS AND SERVICES (BCM5B10) [2019 SYLLABUS]',
    'CODING THEORY (MTH 3E01) [2020 SYLLABUS]',
    'BASIC SANSKRIT (SKT 3E08) (MAL3 C09) [2020 SYLLABUS]',
    'COM5EJ301(1) - Management of Financial Services [Commerce 2024 syllabus]',
    'CSC5EJ303 (2) - Machine Learning Algorithms [Computer Science 2024 syllabus]',
    'CHE5CJ303 - ORGANIC CHEMISTRY - III [Chemistry 2024 syllabus]',
    'PERFORMANCE MANAGEMENT (5.4 BCH) [2017 SYLLABUS]',
    'INTRODUCTION TO GEOMETRY AND THEORY OF EQUATIONS (MTS5B09) [2020 SYLLABUS]'
]

for tc in test_cases:
    res = parse_seating_coursename(tc)
    print(f"Input: {tc}")
    print(f"  Title: '{res['title']}' | Codes: {res['codes']} | Year: '{res['syllabus_year']}'\n")

