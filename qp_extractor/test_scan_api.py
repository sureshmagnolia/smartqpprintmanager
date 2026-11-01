import sys
sys.stdout.reconfigure(encoding='utf-8')
import urllib.request, json

req = urllib.request.Request(
    'http://127.0.0.1:5050/api/scan-folder',
    data=json.dumps({'folder_path': r'C:\Users\sures\Downloads\29-10'}).encode('utf-8'),
    headers={'Content-Type': 'application/json'}
)
resp = urllib.request.urlopen(req)
data = json.loads(resp.read().decode('utf-8'))
print('Total extracted PDFs:', data.get('pdf_count'))
print("-" * 75)
matching = data.get('matching') or {}
summary = matching.get('summary', {})
print("Matching stats:", summary)
print("-" * 75)
for m in matching.get('matched_entries', []):
    status = m.get('matchStatus')
    room = m.get('roomSerial')
    cname = m.get('courseName')
    qp = m.get('matchedQpCode')
    pdf = m.get('matchedPdfFilename')
    print(f"Room {room:<2} | Status: {status:<8} | QP: {qp:<10} | Course: {cname}")
