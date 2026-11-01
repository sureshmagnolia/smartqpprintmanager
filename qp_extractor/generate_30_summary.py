import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

backup_path = r'C:\Users\sures\Downloads\ExamFlow_Full_Backup_2026-10-31.json'
with open(backup_path, encoding='utf-8') as f:
    backup = json.load(f)

allotment = json.loads(backup.get('examRoomAllotment', '{}'))
sess = '30.10.2026 | 02:00 PM'
date, time = sess.split(' | ')
rooms = allotment.get(sess, [])

summary_list = []
for r_idx, r in enumerate(rooms, 1):
    stream = r.get('stream', 'Regular')
    c_counts = {}
    c_scribes = {}
    for s in r.get('students', []):
        c = s.get('Course', '')
        c_counts[c] = c_counts.get(c, 0) + 1
        if s.get('hasScribe') or s.get('scribe'):
            c_scribes[c] = c_scribes.get(c, 0) + 1
            
    for c, cnt in c_counts.items():
        time_clean = time.replace(':', '-')
        summary_list.append({
            'stream': stream,
            'date': date,
            'time': time,
            'roomSerial': r_idx,
            'qpCode': '',
            'courseName': c,
            'count': cnt,
            'scribeCount': c_scribes.get(c, 0),
            'pdfFileName': f'{stream}_{date}_{time_clean}__{c}.pdf'
        })

out_path = r'C:\Users\sures\Downloads\30-10-26\Room_Wise_Seating_Summary_2026-10-30.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(summary_list, f, indent=2)

print(f"Generated {len(summary_list)} entries for {sess}")
print(f"Saved to {out_path}")

