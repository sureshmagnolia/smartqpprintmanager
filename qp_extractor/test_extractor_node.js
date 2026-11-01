const fs = require('fs');
const path = require('path');
const pdfjs = require('C:/Users/sures/.gemini/antigravity/scratch/qp_extractor/pdf.min.js');
pdfjs.GlobalWorkerOptions.workerSrc = 'C:/Users/sures/.gemini/antigravity/scratch/qp_extractor/pdf.worker.min.js';
const Matcher = require('C:/Users/sures/.gemini/antigravity/scratch/qp_extractor/matcher.js');

const QP_CODE_REGEX = /(?:QP\s*Code\s*[:\-]?\s*|Code\s*[:\-]?\s*|(?:^|[^\w]))([A-Z])\s*(\d{5,6})([A-Z])?(?:[^\w]|$)/i;

function parseFilenameMetadata(filename) {
  if (!filename) return {};
  const cleanName = filename.replace(/\.pdf$/i, '').trim();
  const parts = cleanName.split('_');

  let exam_type = null;
  let exam_date = null;
  let exam_time = null;
  let qp_code = null;
  let course_name = null;
  let course_code = null;

  for (const p of parts) {
    const pt = p.trim();
    if (!pt) continue;

    // Skip numbers / timestamps (e.g. 1793342140511)
    if (/^\d{6,}$/.test(pt)) continue;

    if (/^(REG|REGULAR)$/i.test(pt)) {
      exam_type = 'Regular';
    } else if (/^(SDE|EDE|PRIVATE)$/i.test(pt)) {
      exam_type = 'SDE';
    } else if (/^\d{2}\.\d{2}\.\d{2,4}$/.test(pt)) {
      exam_date = pt;
    } else if (/^\d{1,2}[\:\_\-]\d{2}\s*(AM|PM)?$/i.test(pt) || /^(FN|AN)$/i.test(pt)) {
      exam_time = pt.replace('_', ':');
    } else {
      const mQp = pt.match(/^([A-Za-z])?(\d{5,6})([A-Za-z])?$/);
      if (mQp) {
        const letter = mQp[1] ? mQp[1].toUpperCase() : 'D';
        const digits = mQp[2];
        let suffix = mQp[3] ? mQp[3].toUpperCase() : '';
        if (!suffix && (/SDE/i.test(cleanName) || new RegExp(digits + 'A', 'i').test(cleanName))) {
          suffix = 'A';
        }
        qp_code = `${letter} ${digits}${suffix}`;
      } else {
        let segment = pt;
        const parMatches = Array.from(segment.matchAll(/\((.*?)\)/g));
        if (parMatches.length > 0) {
          course_code = parMatches[parMatches.length - 1][1].trim();
          course_name = segment.replace(/\(.*?\)/g, '').replace(/[\-\u2010-\u2015\u2212\xad]+$/, '').trim();
        } else if (!/^\d+$/.test(segment) && segment.length > 2) {
          course_name = segment;
        }
      }
    }
  }

  return { exam_type, exam_date, exam_time, qp_code, course_name, course_code };
}

async function extractFromPdfFile(filePath) {
  const filename = path.basename(filePath);
  const data = new Uint8Array(fs.readFileSync(filePath));
  const doc = await pdfjs.getDocument({ data, isEvalSupported: false }).promise;
  const page = await doc.getPage(1);
  const tc = await page.getTextContent();

  const lineMap = new Map();
  for (const it of tc.items) {
    if (!it.str) continue;
    const y = Math.round(it.transform[5]);
    let foundKey = null;
    for (const k of lineMap.keys()) {
      if (Math.abs(k - y) <= 4) {
        foundKey = k;
        break;
      }
    }
    if (foundKey == null) {
      foundKey = y;
      lineMap.set(foundKey, []);
    }
    lineMap.get(foundKey).push(it);
  }

  const sortedY = Array.from(lineMap.keys()).sort((a, b) => b - a);
  const lines = sortedY.map(y => {
    const items = lineMap.get(y).sort((a, b) => a.transform[4] - b.transform[4]);
    return items.map(it => it.str).join('').trim();
  }).filter(l => l.length > 0);

  // 1. QP Code
  let qp_code = null;
  for (const l of lines.slice(0, 15)) {
    const m = l.match(QP_CODE_REGEX);
    if (m) {
      const letter = m[1].toUpperCase();
      const digits = m[2];
      let suffix = m[3] ? m[3].toUpperCase() : '';
      qp_code = `${letter} ${digits}${suffix}`;
      break;
    }
  }
  if (!qp_code) {
    const m = filename.match(/(?:^|[_\-\s])([A-Za-z])?(\d{5,6})([A-Za-z])?(?:[_\-\s]|$)/);
    if (m) {
      const letter = m[1] ? m[1].toUpperCase() : 'D';
      const digits = m[2];
      let suffix = m[3] ? m[3].toUpperCase() : '';
      qp_code = `${letter} ${digits}${suffix}`;
    }
  }

  // 2. Syllabus Year
  let syllabus_year = null;
  for (const l of lines) {
    const mSyl = l.match(/(?:(20\d\d)\s*[\-\u2010-\u2015\u2212\xad]\s*(20\d\d)\s*(?:Admissions?|Syllabus)?|\((20\d\d)\s*Admission\s*Onwards?\)|(?:Syllabus\s*Year\s*[:\-]?\s*|(?:^|[^\d])\(?)(20\d\d)(?:\s*Syllabus|\s*Admissions?\)?))/i);
    if (mSyl) {
      syllabus_year = mSyl[1] ? `${mSyl[1]}-${mSyl[2]}` : (mSyl[3] || mSyl[4]);
      break;
    }
  }
  if (!syllabus_year) {
    for (const l of lines) {
      const m = l.match(/\b(201\d|202\d)\b/);
      if (m && !l.includes('EXAMINATION')) {
        syllabus_year = m[1];
        break;
      }
    }
  }

  // 3. Course Code & Title
  let course_code = null;
  let course_name = null;
  let full_title = null;

  for (let i = 0; i < Math.min(lines.length, 25); i++) {
    const l = lines[i];
    if (/DEGREE EXAMINATION|NOVEMBER|PAGES|REGISTER|TURN OVER|TIME\s*:|MAXIMUM|NAME\b/i.test(l)) continue;

    // Compound code + delimiter + title
    const mCompound = l.match(/^((?:[A-Za-z]{2,4}\s*\d[A-Za-z0-9\(\)\.\s\-\u2010-\u2015\u2212]*?(?:\s*[\/]\s*[A-Za-z]{2,4}\s*\d[A-Za-z0-9\(\)\.\s\-\u2010-\u2015\u2212]*?)*))(?:\s*[\-\u2010-\u2015\u2212\xad]+\s*|\s{2,}|\s+(?=[A-Z\u0D00-\u0D7F\u0B80-\u0BFF]))(.+)$/);
    if (mCompound && /[A-Za-z]{2,4}\s*\d/i.test(mCompound[1])) {
      const cCand = mCompound[1].trim();
      const tCand = mCompound[2].trim();
      if (cCand.length <= 40 && tCand.length >= 3 && !/^(Section|Part|Time|Marks)/i.test(tCand)) {
        course_code = cCand;
        course_name = tCand;
        full_title = l;
        break;
      }
    }

    // Code on line i, Title on line i+1
    const mCodeOnly = l.match(/^([A-Za-z]{2,4}\s*\d[A-Za-z0-9\(\)\/\.\s\-\u2010-\u2015\u2212]+)$/);
    if (mCodeOnly && i + 1 < lines.length) {
      const nextL = lines[i + 1].trim();
      if (nextL.length >= 4 && !/ADMISSION|SEMESTER|EXAMINATION|MAXIMUM|TIME|SECTION|PART\b/i.test(nextL)) {
        course_code = mCodeOnly[1].trim();
        course_name = nextL;
        full_title = `${course_code} - ${course_name}`;
        break;
      }
    }
  }

  // Fallback to filename metadata
  const fn_meta = parseFilenameMetadata(filename);
  if (!course_code && fn_meta.course_code) course_code = fn_meta.course_code;
  if (!course_name && fn_meta.course_name && !/^\d+$/.test(fn_meta.course_name)) course_name = fn_meta.course_name;
  if (!qp_code && fn_meta.qp_code) qp_code = fn_meta.qp_code;

  return {
    filename,
    qp_code,
    course_code,
    course_name,
    syllabus_year
  };
}

async function run() {
  const dir = 'C:/Users/sures/Downloads/30-10-26';
  const pdfFiles = fs.readdirSync(dir).filter(f => f.endsWith('.pdf'));
  const qpRecords = [];
  for (const f of pdfFiles) {
    const res = await extractFromPdfFile(path.join(dir, f));
    qpRecords.push(res);
  }

  const seatingData = JSON.parse(fs.readFileSync(path.join(dir, 'Room_Wise_Seating_Summary_2026-10-30.json'), 'utf8'));

  const [matchedEntries, qpDist, stats] = Matcher.matchAllRooms(seatingData, qpRecords);

  console.log('\n=== NODE / JS MATCHING VERIFICATION ===');
  console.log('Total entries:', stats.total_entries);
  console.log('Full matches:', stats.full_matches);
  console.log('Unmatched:', stats.unmatched);
  console.log('Match rate:', stats.match_rate_pct + '%');
  console.log('Unique QPs matched:', stats.unique_qps_matched);

  const matchedRooms = matchedEntries.filter(e => e.matchStatus === 'EXACT');
  console.log('\nSample Matched Rooms (first 5):');
  matchedRooms.slice(0, 5).forEach(m => {
    console.log(`Room ${m.roomSerial} (${m.stream}): ${m.courseName} => QP: ${m.qpCode} | PDF: ${m.matchedPdfFilename} [Score: ${m.matchScore}]`);
  });

  const unmatchedRooms = matchedEntries.filter(e => e.matchStatus === 'UNMATCHED');
  console.log('\nSample Unmatched Rooms (first 5):');
  unmatchedRooms.slice(0, 5).forEach(m => {
    console.log(`Room ${m.roomSerial} (${m.stream}): ${m.courseName} => ${m.matchReasons[0]}`);
  });
}

run();
