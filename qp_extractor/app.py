"""
Question Paper Data Extractor Web Application
=============================================
A lightweight, fast web application for uploading and extracting metadata
from Calicut University Question Paper PDFs and matching with ExamFlow Room Seating JSON.

Runs on Python's built-in libraries + Bottle.
Includes full CORS support for both http:// and file:/// execution.
"""

import os
import sys
import json
import webbrowser
import threading
from pathlib import Path
from typing import Dict, Any, List

# Add current directory to path
APP_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(APP_DIR))

from bottle import Bottle, request, response, static_file, run
from extractor import extract_qp_from_bytes, extract_all_qps_in_folder, extract_qp_data
from matcher import match_all_rooms, export_populated_seating_json, parse_seating_coursename

app = Bottle()

DEFAULT_QP_FOLDER = Path(r"C:\Users\sures\Downloads\QP")
DEFAULT_30_FOLDER = Path(r"C:\Users\sures\Downloads\30-10-26")


@app.hook('after_request')
def enable_cors():
    """Enable full CORS and Private Network Access for browser & file:/// requests."""
    response.headers['Access-Control-Allow-Origin'] = '*'
    response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
    response.headers['Access-Control-Allow-Headers'] = 'Origin, Accept, Content-Type, X-Requested-With'
    response.headers['Access-Control-Allow-Private-Network'] = 'true'


@app.route('/', method=['GET', 'OPTIONS'])
def index():
    if request.method == 'OPTIONS':
        return {}
    return static_file("index.html", root=str(APP_DIR))


@app.route('/static/<filepath:path>')
def server_static(filepath):
    return static_file(filepath, root=str(APP_DIR / "static"))


@app.route('/<filepath:re:.*\\.(js|css|html|json)>')
def server_root_static(filepath):
    return static_file(filepath, root=str(APP_DIR))


@app.route("/api/extract", method=["POST", "OPTIONS"])
def api_extract():
    """
    Receives multipart file uploads of PDF question papers,
    extracts structured metadata, and returns JSON.
    """
    if request.method == 'OPTIONS':
        return {}

    uploads = request.files.getall("files")
    if not uploads:
        single = request.files.get("file")
        if single:
            uploads = [single]

    if not uploads:
        response.status = 400
        return {"error": "No PDF files provided"}

    results = []
    for upload in uploads:
        try:
            pdf_bytes = upload.file.read()
            filename = upload.filename or "uploaded.pdf"
            data = extract_qp_from_bytes(pdf_bytes, filename=filename)
            results.append(data)
        except Exception as e:
            results.append({
                "filename": getattr(upload, "filename", "unknown.pdf"),
                "error": str(e),
                "qp_code": None,
                "course_name": None,
                "course_code": None,
                "full_course_title": None,
                "syllabus_year": None,
                "syllabus_text": None,
                "degree_or_subject": None,
                "examination": None
            })

    response.content_type = "application/json; charset=utf-8"
    return json.dumps(results, ensure_ascii=False)


@app.route("/api/match-rooms", method=["POST", "OPTIONS"])
def api_match_rooms():
    """
    Matches Seating Summary JSON entries with extracted QP records.
    Payload can be:
    {
      "seating_entries": [...],
      "qp_records": [...]
    }
    """
    if request.method == 'OPTIONS':
        return {}

    try:
        data = request.json
        if not data:
            data = json.loads(request.body.read().decode('utf-8'))
            
        seating_entries = data.get("seating_entries", [])
        qp_records = data.get("qp_records", [])
        mode = data.get("mode", "strict")

        if not seating_entries:
            response.status = 400
            return {"error": "No seating entries provided"}

        matched_entries, qp_distribution, summary = match_all_rooms(seating_entries, qp_records, mode=mode)

        result = {
            "summary": summary,
            "matched_entries": matched_entries,
            "qp_distribution": qp_distribution
        }
        response.content_type = "application/json; charset=utf-8"
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        response.status = 500
        return {"error": str(e)}


@app.route("/api/scan-folder", method=["POST", "GET", "OPTIONS"])
def api_scan_folder():
    """
    Scans a local folder on disk, extracts all PDFs, finds any Seating JSON,
    and runs the room matcher if seating JSON exists.
    """
    if request.method == 'OPTIONS':
        return {}

    folder_path = None
    if request.method == 'POST':
        try:
            body = request.json or {}
            folder_path = body.get("folder_path")
        except:
            folder_path = None
    if not folder_path:
        folder_path = request.query.get("folder") or str(DEFAULT_30_FOLDER)

    target_dir = Path(folder_path)
    if not target_dir.exists():
        response.status = 404
        return {"error": f"Folder not found: {folder_path}"}

    # Find all PDFs
    pdf_files = sorted(target_dir.glob("*.pdf"))
    pdf_results = [extract_qp_data(p) for p in pdf_files]

    # Find any Seating JSON files in folder
    json_files = sorted(target_dir.glob("*Seating*.json")) + sorted(target_dir.glob("*.json"))
    seating_entries = []
    seating_filename = None
    for jf in json_files:
        try:
            with open(jf, encoding='utf-8') as f:
                content = json.load(f)
                if isinstance(content, list) and len(content) > 0 and 'courseName' in content[0]:
                    seating_entries = content
                    seating_filename = jf.name
                    break
        except:
            pass

    mode = "strict"
    try:
        if request.method == 'POST' and request.json:
            mode = request.json.get("mode", "strict")
        elif request.query.get("mode"):
            mode = request.query.get("mode")
    except:
        pass

    match_result = None
    if seating_entries:
        matched_entries, qp_distribution, summary = match_all_rooms(seating_entries, pdf_results, mode=mode)
        match_result = {
            "seating_filename": seating_filename,
            "summary": summary,
            "matched_entries": matched_entries,
            "qp_distribution": qp_distribution
        }

    response.content_type = "application/json; charset=utf-8"
    return json.dumps({
        "folder": str(target_dir),
        "pdf_count": len(pdf_results),
        "qp_records": pdf_results,
        "matching": match_result
    }, ensure_ascii=False)


@app.route("/api/export-seating", method=["POST", "OPTIONS"])
def api_export_seating():
    """
    Receives matched entries and returns downloadable populated JSON.
    """
    if request.method == 'OPTIONS':
        return {}

    try:
        data = request.json or {}
        matched_entries = data.get("matched_entries", [])
        json_str = export_populated_seating_json(matched_entries)
        
        response.content_type = "application/json; charset=utf-8"
        response.headers['Content-Disposition'] = 'attachment; filename="Room_Wise_Seating_Summary_Populated.json"'
        return json_str
    except Exception as e:
        response.status = 500
        return {"error": str(e)}


@app.route("/api/deep-scan", method=["POST", "GET", "OPTIONS"])
def api_deep_scan():
    """
    Multi-wave deep repository scan across known PDF directories:
    - 30-10-26
    - QP
    - Done
    - 10.07.26FN
    Finds all question papers and matches them with seating entries if provided.
    """
    if request.method == 'OPTIONS':
        return {}

    candidate_dirs = [
        DEFAULT_30_FOLDER,
        DEFAULT_QP_FOLDER,
        Path(r"C:\Users\sures\Downloads\Done-20261101T034135Z-1-001\Done"),
        Path(r"C:\Users\sures\Downloads\10.07.26FN-20261101T035950Z-1-001\10.07.26FN")
    ]

    all_qps = []
    seen_files = set()

    for d in candidate_dirs:
        if d.exists() and d.is_dir():
            for p in sorted(d.glob("*.pdf")):
                if p.name not in seen_files:
                    seen_files.add(p.name)
                    rec = extract_qp_data(p)
                    rec['source_folder'] = d.name
                    rec['full_path'] = str(p)
                    all_qps.append(rec)

    seating_entries = []
    if request.method == 'POST':
        try:
            data = request.json or {}
            seating_entries = data.get("seating_entries", [])
        except:
            seating_entries = []

    match_result = None
    if seating_entries:
        matched_entries, qp_distribution, summary = match_all_rooms(seating_entries, all_qps)
        match_result = {
            "summary": summary,
            "matched_entries": matched_entries,
            "qp_distribution": qp_distribution
        }

    response.content_type = "application/json; charset=utf-8"
    return json.dumps({
        "total_qps_found": len(all_qps),
        "qp_records": all_qps,
        "matching": match_result
    }, ensure_ascii=False)


@app.route("/api/load-sample", method=["GET", "OPTIONS"])
def api_load_sample():
    r"""
    Loads sample QPs from C:\Users\sures\Downloads\QP
    """
    if request.method == 'OPTIONS':
        return {}

    if not DEFAULT_QP_FOLDER.exists():
        response.status = 404
        return {"error": f"Folder {DEFAULT_QP_FOLDER} not found"}

    results = extract_all_qps_in_folder(DEFAULT_QP_FOLDER)
    response.content_type = "application/json; charset=utf-8"
    return json.dumps(results, ensure_ascii=False)


def open_browser(port):
    """Opens browser after server starts."""
    webbrowser.open_new_tab(f"http://localhost:{port}")


def main():
    port = int(os.environ.get("PORT", 5050))
    host = os.environ.get("HOST", "127.0.0.1")

    print("=" * 70)
    print("  Question Paper Extractor & Room Matcher Web App")
    print(f"  Server running at: http://localhost:{port}")
    print("  CORS enabled for file:/// and cross-origin access")
    print("  Press Ctrl+C to stop the server")
    print("=" * 70)

    if "--no-browser" not in sys.argv:
        threading.Timer(1.2, open_browser, args=[port]).start()

    run(app, host=host, port=port, quiet=False)


if __name__ == "__main__":
    main()
