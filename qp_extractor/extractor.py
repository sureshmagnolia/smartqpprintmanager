"""
Calicut University Question Paper (QP) Data Extractor
=====================================================
Analyzes PDF question papers and extracts:
- QP Code (format: 'D 152087', 'D 131729A')
- Course Name (e.g. 'FICTION', 'ECO-FEMINIST-DALIT LITERATURE', 'CO-OPERATIVE THEORY AND PRACTICE')
- Course Code (e.g. 'MAL3E (3)', 'BCM 5B 10')
- Syllabus Year (e.g. '2019', '2024')
- Full Course Title ('<Course Code> - <Course Name>')
- Degree / Subject / Programme
- Examination Details

Includes native PyMuPDF parsing with zero-dependency Windows Media OCR fallback
for vector/scanned documents.
"""

import os
import re
import sys
import json
import argparse
import tempfile
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional, List, Union

try:
    import fitz  # PyMuPDF
except ImportError:
    fitz = None


# Pattern for Calicut University Course Codes (e.g., MAL3E (3), BCM 5B 10, BOT5CJ303, PHY5EJ301(1)/ APH5EJ303(2), MCM 4E (F) 04, ZOL 4E 11 01, ZOL3E09–1)
SINGLE_CODE_PAT = (
    r'[A-Z]{2,4}\s*\d\s*[A-Z]{1,3}'
    r'(?:\s*\([A-Za-z0-9]+\))?'
    r'(?:\s*\d{1,3}(?:\.\d+)?)?'
    r'(?:\s*\([A-Za-z0-9]+\))?'
    r'(?:\s*[-–—\s]\s*\d{1,2})?'
    r'(?:[A-Z])?'
)
COURSE_CODE_REGEX = re.compile(
    rf'({SINGLE_CODE_PAT}(?:\s*/\s*{SINGLE_CODE_PAT})*)',
    re.IGNORECASE
)

# Pattern for QP Code (e.g., 'D 152087', 'D 131729A', 'QP Code: D153242', 'Q.P Code : D153797')
QP_CODE_REGEX = re.compile(
    r'(?:Q\.?P\.?\s*(?:Code)?\s*[:\-\s]*)?([A-Z])\s*(\d{5,7})([A-Za-z])?\b',
    re.IGNORECASE
)

# Pattern for Syllabus Year / Admission Year
SYLLABUS_REGEX = re.compile(
    r'\(?\b(20\d\d)\b(?:\s*(?:to|-)\s*\b(20\d\d)\b)?\s*(?:admissions?|syllabus)[^\n\)]*\)?',
    re.IGNORECASE
)

# Pattern for Degree Examination Header
EXAM_REGEX = re.compile(
    r'(?:FIRST|SECOND|THIRD|FOURTH|FIFTH|SIXTH|SEVENTH|EIGHTH)\s+SEMESTER\s+.*?(?:DEGREE\s+)?EXAMINATION[^\n]*',
    re.IGNORECASE
)


def has_malayalam_ascii_artifact(s: str) -> bool:
    """Detects whether a string is legacy ASCII-encoded font text (e.g. ML-TT Karthika)."""
    if not s:
        return False
    if re.search(r'[\x80-\xff\|\]\[\~\\\{\}\^]', s):
        return True
    if re.search(r'[a-z][A-Z]', s):
        return True
    return False


def resolve_course_title(raw_title: str) -> str:
    """
    Cleans course title and resolves bilingual Malayalam/English titles:
    - 'SlLWVSsL¡ (FOLKLORE)' -> 'FOLKLORE'
    - 'FICTION (WgL-yL-z]-f|U)' -> 'FICTION'
    """
    s = raw_title.strip(" :—–-–\t/")
    m = re.search(r'^(.*?)\s*\((.*?)\)$', s)
    if m:
        part1 = m.group(1).strip(" :—–-–\t/")
        part2 = m.group(2).strip(" :—–-–\t/")
        art1 = has_malayalam_ascii_artifact(part1)
        art2 = has_malayalam_ascii_artifact(part2)
        if art1 and not art2:
            return part2
        elif art2 and not art1:
            return part1

    s = re.sub(r'[\x80-\xff]', '', s)
    s = re.sub(r'\s*[–—]\s*', ' - ', s)
    s = re.sub(r'\s+', ' ', s).strip(" :—–-–\t/")
    return s


# Known Calicut University Subject/Department Abbreviations
CALICUT_DEPT_CODES = [
    'MAL', 'ENG', 'ECO', 'HIS', 'POL', 'MCM', 'COM', 'BCM', 'MTH', 'MAT',
    'PHY', 'CHE', 'BOT', 'ZOL', 'HIN', 'MST', 'STA', 'GEO', 'SOC', 'PSY',
    'PHL', 'ARA', 'SKT', 'URD', 'JOU', 'CSC', 'BBA', 'BCA', 'FT', 'MCJ', 'APH'
]
DEPT_PAT = '|'.join(CALICUT_DEPT_CODES)


def parse_filename_metadata(filename: str) -> Dict[str, Any]:
    """
    Extracts structured examination data embedded in filenames punctuated by '_':
    - exam_type: 'REG' or 'SDE'
    - exam_date: e.g. '10.07.26', '12.01.26'
    - exam_time: e.g. '09:30 AM', '01:30 PM'
    - qp_code: e.g. 'D 142922', 'D 142924A', 'D 131794'
    - course_code: e.g. 'MAL4 E16', 'MAL3 E03', 'ECO3 C11'
    - course_name: e.g. 'FOLKLORE', 'FICTION', 'BASIC ECONOMETRICS'
    - raw_parts: list of string segments split by '_'
    """
    if not filename:
        return {
            "exam_type": None, "exam_date": None, "exam_time": None,
            "qp_code": None, "course_code": None, "course_name": None,
            "raw_parts": []
        }

    name = re.sub(r'\.pdf$', '', filename, flags=re.I).strip()
    raw_parts = [p.strip() for p in name.split('_') if p.strip()]

    exam_type = None
    exam_date = None
    exam_time = None
    qp_code = None
    course_code = None
    course_name = None

    # 1. Exam Type: REG or SDE
    m_type = re.match(r'^(REG|SDE)[_\-\s]', name, re.I)
    if m_type:
        exam_type = m_type.group(1).upper()
        rem = name[m_type.end():]
    else:
        rem = name

    # 2. Exam Date: format DD.MM.YY or DD.MM.YYYY
    m_date = re.search(r'(\d{2}\.\d{2}\.\d{2,4})', rem)
    if m_date:
        exam_date = m_date.group(1)

    # 3. Exam Time: format 09_30 AM or 01:30 PM
    m_time = re.search(r'(\d{1,2})[_\.:](\d{2})\s*(AM|PM)', rem, re.I)
    if m_time:
        exam_time = f"{m_time.group(1).zfill(2)}:{m_time.group(2)} {m_time.group(3).upper()}"

    # 4. QP Code: 5-7 digits + optional letter (e.g. 142922, 142924A, 131794, 150113)
    m_qp = re.search(r'(?:_|^)([A-Za-z]?\s*\d{5,7})([A-Za-z])?(?:_|$)', rem)
    if m_qp:
        val = m_qp.group(1).replace(' ', '').upper()
        if not val.startswith('D') and not val.startswith('C'):
            val = 'D ' + val
        else:
            val = val[0] + ' ' + val[1:]
        suf = (m_qp.group(2) or '').upper()
        if not suf and exam_type == 'SDE':
            suf = 'A'
        qp_code = f"{val}{suf}"
        after_qp = rem[m_qp.end():].strip(' _-')
    else:
        m_qp2 = re.search(r'\b(\d{6})([A-Za-z])?\b', rem)
        if m_qp2:
            dig = m_qp2.group(1)
            suf = (m_qp2.group(2) or '').upper()
            if not suf and exam_type == 'SDE':
                suf = 'A'
            qp_code = f"D {dig}{suf}"
            after_qp = rem[m_qp2.end():].strip(' _-')
        else:
            after_qp = rem

    # 5. Course Name & Course Code from after_qp
    if after_qp and not re.match(r'^\d+$', after_qp):
        # Format A: title--(CODE)
        m_dash = re.search(r'^(.*?)\s*[-–—]{1,2}\s*\(?(.*?)\)?$', after_qp)
        if m_dash and m_dash.group(2):
            c_name = m_dash.group(1).strip(' _-')
            c_code = m_dash.group(2).strip(' _-()')
            c_name = re.sub(r'\s*_\s*', ' : ', c_name)
            course_name = c_name
            course_code = c_code
        else:
            # Format B: FICTIONMAL3 E03 or BASIC ECONOMETRICSECO3 C11
            m_dept = re.search(rf'({DEPT_PAT})\s*(\d\s*[A-Z]{{1,3}}(?:\s*\(?[A-Za-z0-9]+\)?)?(?:\s*\d{{1,3}})?)$', after_qp, re.I)
            if m_dept:
                course_code = m_dept.group(0).strip()
                course_name = after_qp[:m_dept.start()].strip(' _-')
            else:
                m_code = re.search(r'([A-Z]{2,4}\s*\d\s*[A-Z]{1,3}(?:\s*\(?[A-Za-z0-9]+\)?)?(?:\s*\d{1,3})?)$', after_qp, re.I)
                if m_code:
                    course_code = m_code.group(1).strip()
                    course_name = after_qp[:m_code.start()].strip(' _-')
                else:
                    course_name = after_qp

    return {
        "exam_type": exam_type,
        "exam_date": exam_date,
        "exam_time": exam_time,
        "qp_code": qp_code,
        "course_code": course_code,
        "course_name": course_name,
        "raw_parts": raw_parts
    }


def extract_text_from_pdf(pdf_path: Union[str, Path]) -> str:
    """
    Extracts text from the first page of the PDF file.
    Falls back to Windows native Media OCR if text is vector outlines or empty.
    """
    if not fitz:
        raise ImportError("PyMuPDF (fitz) is required. Install via: pip install pymupdf")

    doc = fitz.open(str(pdf_path))
    if len(doc) == 0:
        return ""

    page = doc[0]
    text = page.get_text()

    lines = [l.strip() for l in text.splitlines() if l.strip()]
    meaningful_text = " ".join(lines)
    has_qp_keywords = any(
        kw in meaningful_text.upper()
        for kw in ["SEMESTER", "EXAMINATION", "DEGREE", "SYLLABUS", "ADMISSION", "QP CODE"]
    )

    if not has_qp_keywords or len(meaningful_text) < 60:
        ocr_text = perform_windows_ocr(page)
        if ocr_text and len(ocr_text.strip()) > len(meaningful_text):
            return ocr_text

    return text


def extract_text_from_pdf_bytes(pdf_bytes: bytes, filename: str = "") -> str:
    """
    Extracts text directly from PDF bytes in memory.
    Falls back to Windows native Media OCR if text is vector outlines or empty.
    """
    if not fitz:
        raise ImportError("PyMuPDF (fitz) is required. Install via: pip install pymupdf")

    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    if len(doc) == 0:
        return ""

    page = doc[0]
    text = page.get_text()

    lines = [l.strip() for l in text.splitlines() if l.strip()]
    meaningful_text = " ".join(lines)
    has_qp_keywords = any(
        kw in meaningful_text.upper()
        for kw in ["SEMESTER", "EXAMINATION", "DEGREE", "SYLLABUS", "ADMISSION", "QP CODE"]
    )

    if not has_qp_keywords or len(meaningful_text) < 60:
        ocr_text = perform_windows_ocr(page)
        if ocr_text and len(ocr_text.strip()) > len(meaningful_text):
            return ocr_text

    return text


def perform_windows_ocr(page) -> str:
    """
    Runs Windows built-in Windows.Media.Ocr.OcrEngine via PowerShell.
    Requires no external packages or downloads on Windows 10/11.
    """
    try:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            tmp_path = tmp.name

        pix = page.get_pixmap(dpi=200)
        pix.save(tmp_path)

        ps_script = f"""
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {{
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
}}[0]

function Await($asyncOp, $type) {{
    $asTask = $asTaskGeneric.MakeGenericMethod($type)
    $task = $asTask.Invoke($null, @($asyncOp))
    $task.Wait()
    return $task.Result
}}

[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation.UniversalApiContract, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation.UniversalApiContract, ContentType = WindowsRuntime] | Out-Null

$fileOp = [Windows.Storage.StorageFile]::GetFileFromPathAsync('{tmp_path}')
$file = Await $fileOp ([Windows.Storage.StorageFile])

$streamOp = $file.OpenAsync([Windows.Storage.FileAccessMode]::Read)
$stream = Await $streamOp ([Windows.Storage.Streams.IRandomAccessStream])

$decoderOp = [Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)
$decoder = Await $decoderOp ([Windows.Graphics.Imaging.BitmapDecoder])

$bitmapOp = $decoder.GetSoftwareBitmapAsync()
$bitmap = Await $bitmapOp ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
$ocrOp = $engine.RecognizeAsync($bitmap)
$ocrResult = Await $ocrOp ([Windows.Media.Ocr.OcrResult])

foreach ($line in $ocrResult.Lines) {{
    Write-Output $line.Text
}}
"""
        res = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps_script],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace"
        )

        try:
            os.remove(tmp_path)
        except OSError:
            pass

        return res.stdout if res.returncode == 0 else ""
    except Exception:
        return ""


def parse_question_paper_data(text: str, filename: str = "") -> Dict[str, Any]:
    """
    Parses question paper text and filename, extracting key metadata fields:
    - qp_code: Format 'D 152087' or 'D 131729A'
    - course_name: The descriptive title of the course
    - course_code: The official alphanumeric course identifier
    - full_course_title: '<course_code> - <course_name>'
    - syllabus_year: The syllabus revision or admission year (e.g. '2019', '2024')
    - syllabus_text: Exact wording (e.g. '(2019 Syllabus)', '2024 Admission onwards')
    - degree_or_subject: The degree program or subject discipline
    - examination: Semester and degree examination description
    - filename_data: Extracted fields from filename punctuated by '_' (exam_type, date, time, etc.)
    """
    lines = [l.strip() for l in text.splitlines() if l.strip()]

    # Parse filename metadata (punctuated by '_')
    fn_meta = parse_filename_metadata(filename) if filename else {
        "exam_type": None, "exam_date": None, "exam_time": None,
        "qp_code": None, "course_code": None, "course_name": None,
        "raw_parts": []
    }

    # 1. QP Code Extraction
    qp_code = None
    for l in lines[:15]:
        m = QP_CODE_REGEX.search(l)
        if m:
            letter = m.group(1).upper()
            digits = m.group(2)
            suffix = m.group(3).upper() if m.group(3) else ''
            if not suffix and filename:
                if 'SDE' in filename.upper() or f"{digits}A" in filename.upper():
                    suffix = 'A'
            qp_code = f"{letter} {digits}{suffix}"
            break

    # Fallback to filename if not found in text
    if not qp_code:
        qp_code = fn_meta.get("qp_code")

    # 2. Syllabus Year Extraction
    syllabus_year = None
    syllabus_text = None

    for l in lines[:25]:
        m = SYLLABUS_REGEX.search(l)
        if m:
            syllabus_year = f"{m.group(1)}-{m.group(2)}" if m.group(2) else m.group(1)
            syllabus_text = m.group(0).strip()
            break

    if not syllabus_year:
        for l in lines[:25]:
            m = re.search(r'\(?\b(20\d\d)\b.*?(?:syllabus|admissions?)\)?', l, re.IGNORECASE)
            if m:
                syllabus_year = m.group(1)
                syllabus_text = m.group(0).strip()
                break

    # 3. Course Code & Course Name Extraction
    course_code = None
    course_name = None
    full_course_title = None
    course_line_idx = -1

    stop_idx = len(lines)
    for idx, l in enumerate(lines[:30]):
        if re.search(r'^(?:Part\s+[A-Z]|Section\s+[A-Z]|Answer\s+all|Answer\s+any|Time\s*:|Maximum\s*:)', l, re.IGNORECASE):
            stop_idx = idx
            break

    for idx in range(min(stop_idx, 25)):
        l = lines[idx]
        if re.search(r'(?:SEMESTER|DEGREE|EXAMINATION|CBCSS|CUFYUGP|PAGES\s*:)', l, re.IGNORECASE):
            continue

        m = COURSE_CODE_REGEX.search(l)
        if m:
            c_code = m.group(1).strip()
            rest = l[m.end():].strip(" :—–-–\t/")

            # Check if course title continues on next line
            if idx + 1 < stop_idx:
                nxt = lines[idx + 1].strip()
                is_instr_or_syl = bool(re.search(r'(?:20\d\d|time\s*:|maximum|section|part|answers?\s+should|\(cbcss\))', nxt, re.IGNORECASE))
                
                if (not rest or re.match(r'^[\s\-—–:]+$', rest)) and not is_instr_or_syl:
                    rest = nxt.strip(" :—–-–\t/")
                elif re.search(r'(?:AND|&|,|/|-|OF|FOR|IN|TO|THE|ON|WITH)$', rest, re.IGNORECASE) and not is_instr_or_syl:
                    rest = f"{rest} {nxt.strip(' :—–-–\t/')}"

            course_code = c_code
            course_name = resolve_course_title(rest)
            course_line_idx = idx
            break

    # Fallback to filename course name/code if not found in PDF text
    if not course_code and fn_meta.get("course_code"):
        course_code = fn_meta["course_code"]
    if not course_name and fn_meta.get("course_name"):
        course_name = fn_meta["course_name"]

    if course_code:
        full_course_title = f"{course_code} - {course_name}" if course_name else course_code
    elif course_name:
        full_course_title = course_name

    # 4. Degree / Subject Extraction
    degree_or_subject = None
    if course_line_idx > 0:
        prev = lines[course_line_idx - 1]
        if not re.search(r'(?:SEMESTER|DEGREE|EXAMINATION|PAGES|NAME|REG|TURN|NOVEMBER|202\d|\(CBCSS\)|\(CUFYUGP\)|TOTAL)', prev, re.IGNORECASE):
            degree_or_subject = prev.strip()

    # 5. Examination Details
    exam_name = None
    for idx, l in enumerate(lines[:12]):
        if EXAM_REGEX.search(l):
            exam_name = l
            if idx + 1 < len(lines) and re.search(r'^(?:NOVEMBER|DECEMBER|APRIL|MAY|JUNE|JULY)\s+20\d\d', lines[idx + 1], re.IGNORECASE):
                exam_name += f", {lines[idx + 1]}"
            break

    return {
        "filename": filename,
        "qp_code": qp_code,
        "course_name": course_name,
        "course_code": course_code,
        "full_course_title": full_course_title,
        "syllabus_year": syllabus_year,
        "syllabus_text": syllabus_text,
        "degree_or_subject": degree_or_subject,
        "examination": exam_name,
        # Filename metadata (often punctuated by _)
        "exam_type": fn_meta.get("exam_type"),
        "exam_date": fn_meta.get("exam_date"),
        "exam_time": fn_meta.get("exam_time"),
        "filename_qp_code": fn_meta.get("qp_code"),
        "filename_course_name": fn_meta.get("course_name"),
        "filename_course_code": fn_meta.get("course_code"),
        "filename_raw_parts": fn_meta.get("raw_parts", []),
        "filename_data": fn_meta,
    }


def extract_qp_data(pdf_path: Union[str, Path]) -> Dict[str, Any]:
    """Extract structured question paper data from a single PDF file on disk."""
    path_obj = Path(pdf_path)
    text = extract_text_from_pdf(path_obj)
    return parse_question_paper_data(text, filename=path_obj.name)


def extract_qp_from_bytes(pdf_bytes: bytes, filename: str = "") -> Dict[str, Any]:
    """Extract structured question paper data from PDF bytes in memory."""
    text = extract_text_from_pdf_bytes(pdf_bytes, filename=filename)
    return parse_question_paper_data(text, filename=filename)


def extract_all_qps_in_folder(folder_path: Union[str, Path]) -> List[Dict[str, Any]]:
    """Batch processes all PDF files in a given directory."""
    folder = Path(folder_path)
    pdf_files = sorted(list(folder.glob("*.pdf")))
    results = []
    for pdf in pdf_files:
        try:
            data = extract_qp_data(pdf)
            results.append(data)
        except Exception as e:
            results.append({
                "filename": pdf.name,
                "error": str(e),
                "qp_code": None,
                "course_name": None,
                "course_code": None,
                "full_course_title": None,
                "syllabus_year": None,
                "syllabus_text": None,
                "degree_or_subject": None,
                "examination": None,
            })
    return results


def main():
    parser = argparse.ArgumentParser(
        description="Extract QP Code, Course Name, and Syllabus Year from Question Paper PDFs."
    )
    parser.add_argument("path", help="Path to a PDF file or a folder containing PDF files")
    parser.add_argument("-o", "--output", help="Optional output path for CSV or JSON results", default=None)
    parser.add_argument("--format", choices=["table", "json", "csv"], default="table", help="Output display format")
    parser.add_argument("--table-type", choices=["combined", "filename", "pdf"], default="combined",
                        help="Table view: 'combined' (all data), 'filename' (metadata punctuated by '_'), or 'pdf' (content only)")
    args = parser.parse_args()

    target_path = Path(args.path)
    if not target_path.exists():
        print(f"Error: Path '{args.path}' does not exist.", file=sys.stderr)
        sys.exit(1)

    if target_path.is_file():
        records = [extract_qp_data(target_path)]
    else:
        records = extract_all_qps_in_folder(target_path)

    all_keys = [
        "filename", "qp_code", "course_code", "course_name", "full_course_title",
        "syllabus_year", "exam_type", "exam_date", "exam_time",
        "filename_course_code", "filename_course_name", "degree_or_subject", "examination"
    ]

    if args.output:
        out_file = Path(args.output)
        if out_file.suffix.lower() == ".json":
            with open(out_file, "w", encoding="utf-8") as f:
                json.dump(records, f, indent=2, ensure_ascii=False)
        else:
            import csv
            with open(out_file, "w", newline="", encoding="utf-8-sig") as f:
                writer = csv.DictWriter(f, fieldnames=all_keys, extrasaction="ignore")
                writer.writeheader()
                writer.writerows(records)
        print(f"Results written to: {out_file}")

    if args.format == "json":
        print(json.dumps(records, indent=2, ensure_ascii=False))
    elif args.format == "csv":
        import csv
        writer = csv.DictWriter(sys.stdout, fieldnames=all_keys, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    else:
        if args.table_type == "filename":
            header = f"{'#':<3} | {'Type':<4} | {'Exam Date':<10} | {'Time':<9} | {'QP Code':<9} | {'Course Code':<15} | {'Course Name':<35} | {'Filename Parts (_)':<25}"
            print("-" * len(header))
            print(header)
            print("-" * len(header))
            for idx, r in enumerate(records):
                fn_data = r.get("filename_data") or {}
                et = fn_data.get("exam_type") or "N/A"
                ed = fn_data.get("exam_date") or "N/A"
                ti = fn_data.get("exam_time") or "N/A"
                qp = fn_data.get("qp_code") or r.get("qp_code") or "N/A"
                cc = fn_data.get("course_code") or "N/A"
                cn = fn_data.get("course_name") or "N/A"
                if len(cn) > 33:
                    cn = cn[:30] + "..."
                parts = " _ ".join(fn_data.get("raw_parts", [])[:3])
                if len(parts) > 23:
                    parts = parts[:20] + "..."
                print(f"{idx+1:<3} | {et:<4} | {ed:<10} | {ti:<9} | {qp:<9} | {cc:<15} | {cn:<35} | {parts:<25}")
            print("-" * len(header))

        elif args.table_type == "pdf":
            header = f"{'#':<3} | {'QP Code':<9} | {'Year':<6} | {'Course Code':<20} | {'Course Name':<38} | {'Degree/Subject':<25}"
            print("-" * len(header))
            print(header)
            print("-" * len(header))
            for idx, r in enumerate(records):
                code = r.get("qp_code") or "N/A"
                yr = r.get("syllabus_year") or "N/A"
                cc = r.get("course_code") or "N/A"
                cn = r.get("course_name") or "N/A"
                if len(cn) > 36:
                    cn = cn[:33] + "..."
                subj = r.get("degree_or_subject") or "N/A"
                if len(subj) > 23:
                    subj = subj[:20] + "..."
                print(f"{idx+1:<3} | {code:<9} | {yr:<6} | {cc:<20} | {cn:<38} | {subj:<25}")
            print("-" * len(header))

        else:  # combined
            header = f"{'#':<3} | {'QP Code':<9} | {'Type':<4} | {'Date':<8} | {'Time':<9} | {'Course Code':<16} | {'Course Name':<34} | {'Year':<5}"
            print("-" * len(header))
            print(header)
            print("-" * len(header))
            for idx, r in enumerate(records):
                code = r.get("qp_code") or "N/A"
                et = r.get("exam_type") or "-"
                ed = r.get("exam_date") or "-"
                ti = r.get("exam_time") or "-"
                cc = r.get("course_code") or "N/A"
                cn = r.get("course_name") or "N/A"
                if len(cn) > 32:
                    cn = cn[:29] + "..."
                yr = r.get("syllabus_year") or "-"
                print(f"{idx+1:<3} | {code:<9} | {et:<4} | {ed:<8} | {ti:<9} | {cc:<16} | {cn:<34} | {yr:<5}")
            print("-" * len(header))


if __name__ == "__main__":
    main()
