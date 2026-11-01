/**
 * Calicut University Question Paper & Room Seating Matcher (Client-side & Node.js)
 * ==============================================================================
 * Implements the 3-pillar matching engine:
 *   1. Course Code (spaceless comparison, e.g. 'TML5B10', 'PHY5EJ301', 'MTS5B09')
 *   2. Course Title (normalized string & token overlap, e.g. 'PUTHUMURAI ILAKKIYAVIYAL')
 *   3. Syllabus Year (exact year or Calicut University cycle, e.g. '2019', '2024')
 * 
 * Cross-referenced against both 'courseName' and 'pdfFileName' (__ segment).
 * Strictly prevents false matches between different elective variants (e.g. COM5EJ301 != COM5EJ305).
 */

(function(root, factory) {
  const mod = factory();
  if (typeof define === 'function' && define.amd) {
    define([], () => mod);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = mod;
  }
  if (typeof root !== 'undefined') {
    root.Matcher = mod;
    root.QPMatcher = mod;
  }
}(typeof self !== 'undefined' ? self : (typeof window !== 'undefined' ? window : this), function() {

  function normalizeCode(c) {
    if (!c) return '';
    return String(c).toUpperCase().replace(/[\s\-_]+/g, '').trim();
  }

  function extractAllCodes(text) {
    if (!text) return [];
    const s = String(text).trim();
    const segments = s.split(/[/|]/).map(x => x.trim()).filter(x => x.length > 0);

    const codes = [];
    for (const seg of segments) {
      const clean = seg.toUpperCase().replace(/[\s\-_]+/g, '');
      if (clean) {
        codes.push(clean);
        const withoutParens = clean.replace(/\(.*?\)/g, '');
        if (withoutParens && withoutParens !== clean) {
          codes.push(withoutParens);
        }
        const unparens = clean.replace(/[()]/g, '');
        if (unparens && unparens !== clean) {
          codes.push(unparens);
        }
      }
    }

    // Add O <-> 0 digit substitutions (e.g. PSY5BO5 <-> PSY5B05)
    const expanded = [...codes];
    for (const c of codes) {
      const m = c.match(/^([A-Z]{3,4}\d[A-Z])O(\d+)$/);
      if (m) expanded.push(`${m[1]}0${m[2]}`);
      const m2 = c.match(/^([A-Z]{3,4}\d[A-Z])0(\d+)$/);
      if (m2) expanded.push(`${m2[1]}O${m2[2]}`);
    }

    return Array.from(new Set(expanded));
  }

  function normalizeTitle(t) {
    if (!t) return '';
    let s = String(t).toUpperCase().trim();
    s = s.replace(/[\-\u2010-\u2015\u2212\xad_:\,\.\(\)\[\]\/\'\"\`]+/g, ' ');
    s = s.replace(/\s+/g, ' ');
    return s.trim();
  }

  function parseSeatingCourseName(cname, pdfFileName) {
    const sources = [];
    if (cname) sources.push(String(cname).trim());
    if (pdfFileName) {
      const fnStr = String(pdfFileName).trim();
      if (fnStr.includes('__')) {
        const metaSeg = fnStr.split('__')[1].replace(/\.pdf$/i, '').trim();
        sources.push(metaSeg);
      } else {
        sources.push(fnStr.replace(/\.pdf$/i, '').trim());
      }
    }

    let title = '';
    const codes = [];
    let sylYear = '';
    let sylRaw = '';

    for (const src of sources) {
      let srcClean = src;
      const mSyl = src.match(/\[(.*?)\]/);
      if (mSyl) {
        sylRaw = mSyl[1].trim();
        const mYr = sylRaw.match(/(20\d\d)/);
        if (mYr && !sylYear) sylYear = mYr[1];
        srcClean = src.slice(0, mSyl.index).trim();
      }

      // Format A: CODE - Title
      const mCodeDash = srcClean.match(/^([A-Za-z]{2,}\d[A-Za-z0-9\(\)\/\s\.]*|\d+\.\d+\s*[A-Za-z]+)\s*[\-\u2010-\u2015\u2212\xad]+\s*(.+)$/);
      if (mCodeDash) {
        const codeCand = mCodeDash[1].trim();
        const titleCand = mCodeDash[2].trim();
        if (!codes.includes(codeCand)) codes.push(codeCand);
        if (!title) title = titleCand;
      }

      // Format B: Title (CODE)
      const parMatches = Array.from(srcClean.matchAll(/\((.*?)\)/g));
      if (parMatches.length > 0) {
        let titleEnd = null;
        for (const pm of parMatches) {
          const inner = pm[1].trim();
          if (/[A-Za-z]/.test(inner) && /\d/.test(inner)) {
            if (!codes.includes(inner)) codes.push(inner);
            if (titleEnd === null) titleEnd = pm.index;
          } else if (!sylYear && /^20\d\d$/.test(inner)) {
            sylYear = inner;
          }
        }
        if (!title && titleEnd !== null) {
          title = srcClean.slice(0, titleEnd).trim();
        }
      }

      // Format C: Leading code with space
      if (codes.length === 0) {
        const mLead = srcClean.match(/^([A-Za-z]{2,4}\s*\d[A-Za-z0-9\s]{1,8}?)\s+([A-Za-z]{3,}.*)$/);
        if (mLead && /[A-Za-z]/.test(mLead[1]) && /\d/.test(mLead[1])) {
          codes.push(mLead[1].trim());
          if (!title) title = mLead[2].trim();
        }
      }

      // Fallback
      if (!title) title = srcClean;
    }

    title = title.replace(/[\-\u2010-\u2015\u2212\xad:\,\.\s]+$/, '').trim();

    const normCodes = [];
    for (const c of codes) {
      normCodes.push(...extractAllCodes(c));
    }

    return {
      original: cname || pdfFileName || '',
      title,
      norm_title: normalizeTitle(title),
      codes,
      normalized_codes: Array.from(new Set(normCodes)),
      syllabus_year: sylYear,
      syllabus_raw: sylRaw
    };
  }

  function isYearCompatible(seatYear, qpYear) {
    if (!seatYear || !qpYear) return { compatible: true, reason: 'Year neutral (unspecified)' };
    const sy = String(seatYear).trim();
    const qy = String(qpYear).trim();
    if (sy === qy || qy.includes(sy)) {
      return { compatible: true, reason: `Exact syllabus match (${sy})` };
    }
    if (sy === '2020' && ['2020', '2021', '2022', '2023'].some(y => qy.includes(y))) {
      return { compatible: true, reason: `Syllabus cycle match (${sy} ~ ${qy})` };
    }
    if (sy === '2019' && ['2019', '2020', '2021', '2022', '2023'].some(y => qy.includes(y))) {
      return { compatible: true, reason: `Syllabus cycle match (${sy} ~ ${qy})` };
    }
    if (sy === '2024' && qy.includes('2024')) {
      return { compatible: true, reason: 'Exact 2024 FYUGP syllabus match' };
    }
    return { compatible: false, reason: `Syllabus mismatch (${sy} vs ${qy})` };
  }

  const DEPARTMENT_ALIASES = {
    "CSC": "ELE",
    "BCH": "COM",
    "BCM": "COM",
    "BBA": "COM",
    "PSY": "PHL",
    "HIN": "GEO",
    "SAN": "GEO"
  };

  function getDeptPrefix(code) {
    const norm = normalizeCode(code);
    const m = norm.match(/([A-Za-z]{3})/);
    return m ? m[1].toUpperCase() : '';
  }

  function scoreQpToSeating(roomEntry, qp, mode = 'strict') {
    const roomParsed = parseSeatingCourseName(roomEntry.courseName || '', roomEntry.pdfFileName || '');
    const roomStream = roomEntry.stream || 'Regular';

    // 1. Course Code matching
    const qpCodes = [];
    if (qp.course_code) qpCodes.push(...extractAllCodes(qp.course_code));
    if (qp.filename_data && qp.filename_data.course_code) qpCodes.push(...extractAllCodes(qp.filename_data.course_code));
    const uniqueQpCodes = Array.from(new Set(qpCodes));

    const roomTitle = roomParsed.norm_title || '';
    const qpTitles = [];
    if (qp.course_name) qpTitles.push(normalizeTitle(qp.course_name));
    if (qp.filename_data && qp.filename_data.course_name) qpTitles.push(normalizeTitle(qp.filename_data.course_name));

    if (mode === 'department') {
      // TIER 1: Exact Course Code Match
      for (const rc of roomParsed.normalized_codes) {
        for (const qc of uniqueQpCodes) {
          if (rc === qc) {
            return { score: 150, status: 'EXACT', reasons: [`Exact course code match (${rc})`] };
          }
          if (rc.length >= 5 && qc.length >= 5 && (rc.includes(qc) || qc.includes(rc))) {
            return { score: 140, status: 'EXACT', reasons: [`Sub-code match (${rc} ~ ${qc})`] };
          }
        }
      }

      // TIER 1b: Title translation (e.g. Folklore == Nadodi Vijnaneeyam)
      if (roomTitle.includes('FOLKLORE')) {
        for (const qt of qpTitles) {
          if (qt.includes('NADODI') || qt.includes('VIJNANEEYAM')) {
            return { score: 130, status: 'TRANSLATION', reasons: ['Title translation (Folklore ↔ Nadodi Vijnaneeyam)'] };
          }
        }
        if (uniqueQpCodes.some(c => c.includes('MAL'))) {
          return { score: 115, status: 'DEPT_ELECTIVE', reasons: ['Malayalam department elective paper'] };
        }
      }

      // TIER 2: Department Elective Paper (e.g. COM5EJ301 vs COM5EJ305, BOT5EJ301 vs BOT5EJ302)
      for (const rc of roomParsed.normalized_codes) {
        for (const qc of uniqueQpCodes) {
          if (rc.length >= 7 && qc.length >= 7 && rc.slice(0, 6) === qc.slice(0, 6)) {
            const deptName = rc.slice(0, 3);
            return { score: 110, status: 'DEPT_ELECTIVE', reasons: [`Department elective match (${deptName} 5EJ30x)`] };
          }
        }
      }

      // TIER 3: Allied Department / Faculty Match
      for (const rc of roomParsed.normalized_codes) {
        const rDept = getDeptPrefix(rc);
        for (const qc of uniqueQpCodes) {
          const qDept = getDeptPrefix(qc);
          if (rDept && qDept) {
            if (DEPARTMENT_ALIASES[rDept] === qDept) {
              return { score: 95, status: 'ALLIED_ELECTIVE', reasons: [`Allied department match (${rDept} → ${qDept})`] };
            }
            if (['BCM', 'BBA', 'BCH'].includes(rDept) && qDept === 'COM') {
              return { score: 95, status: 'ALLIED_ELECTIVE', reasons: [`Commerce & Management faculty match (${rDept} → COM)`] };
            }
            if (rDept === 'PSY' && qDept === 'PHL') {
              return { score: 90, status: 'ALLIED_ELECTIVE', reasons: ['Allied humanities match (Psychology → Philosophy)'] };
            }
          }
        }
      }

      // TIER 4: Session Allocation for remaining language electives
      for (const rc of roomParsed.normalized_codes) {
        const rDept = getDeptPrefix(rc);
        if (['HIN', 'SAN'].includes(rDept)) {
          for (const qc of uniqueQpCodes) {
            const qDept = getDeptPrefix(qc);
            if (qDept === 'GEO') {
              return { score: 80, status: 'ALLIED_ELECTIVE', reasons: [`Session allocation for ${rDept} elective`] };
            }
          }
        }
      }

      return { score: 0, status: 'UNMATCHED', reasons: ['No matching QP found in batch'] };
    }

    // --- Mode: 'strict' ---
    let score = 0;
    const reasons = [];
    let codeMatched = false;
    let differentElective = false;

    for (const rc of roomParsed.normalized_codes) {
      for (const qc of uniqueQpCodes) {
        if (rc === qc) {
          score += 70;
          codeMatched = true;
          reasons.push(`Exact spaceless code match (${rc})`);
          break;
        } else if (rc.length >= 5 && qc.length >= 5 && (rc.includes(qc) || qc.includes(rc))) {
          score += 60;
          codeMatched = true;
          reasons.push(`Sub-code match (${rc} ~ ${qc})`);
          break;
        } else if (rc.length >= 7 && qc.length >= 7 && rc.slice(0, 6) === qc.slice(0, 6) && rc !== qc) {
          differentElective = true;
          reasons.push(`Different elective paper in same department (${rc} vs ${qc})`);
        }
      }
      if (codeMatched) break;
    }

    let titleMatched = false;
    let titleMismatch = false;

    for (const qt of qpTitles) {
      if (!qt || !roomTitle) continue;
      if (roomTitle === qt) {
        score += 40;
        titleMatched = true;
        reasons.push(`Exact title match ('${roomTitle}')`);
        break;
      } else if (roomTitle.length > 6 && qt.length > 6 && (roomTitle.includes(qt) || qt.includes(roomTitle))) {
        score += 30;
        titleMatched = true;
        reasons.push('Substring title match');
        break;
      } else {
        const rWords = new Set(roomTitle.split(/\s+/).filter(w => w.length > 3));
        const qWords = new Set(qt.split(/\s+/).filter(w => w.length > 3));
        if (rWords.size > 0 && qWords.size > 0) {
          const common = new Set([...rWords].filter(x => qWords.has(x)));
          if (common.size > 0) {
            const ratio = common.size / Math.min(rWords.size, qWords.size);
            if (ratio >= 0.5) {
              const pts = Math.round(30 * ratio);
              score += pts;
              titleMatched = true;
              reasons.push(`Title token overlap (${pts} pts)`);
              break;
            }
          }
        }
      }
    }

    // Check if QP has vernacular title in native script or belongs to a language department
    const isVernacularTitle = qpTitles.some(qt => /[\u0D00-\u0D7F\u0B80-\u0BFF\u0900-\u097F\u0600-\u06FF]/.test(qt)) ||
      rCodes.some(rc => /^(MAL|TML|HIN|SKT|ARB|URD)/.test(rc));

    if (!titleMatched && codeMatched && isVernacularTitle) {
      titleMatched = true;
      score += 35;
      reasons.push("Vernacular title in native script for language course");
    }

    // Title mismatch penalty
    if (!titleMatched && roomTitle && roomTitle.length > 6) {
      for (const qt of qpTitles) {
        if (qt && qt.length > 6) {
          if (codeMatched && isVernacularTitle) continue;
          const rWords = new Set(roomTitle.split(/\s+/).filter(w => w.length > 3));
          const qWords = new Set(qt.split(/\s+/).filter(w => w.length > 3));
          const common = new Set([...rWords].filter(x => qWords.has(x)));
          if (rWords.size > 0 && qWords.size > 0 && common.size === 0) {
            titleMismatch = true;
            score -= 40;
            reasons.push(`Subject conflict: '${roomTitle}' != '${qt}'`);
            break;
          }
        }
      }
    }

    if (differentElective && !titleMatched) {
      score -= 50;
    }

    // 3. Stream match
    const stream = (roomStream || '').toUpperCase();
    const qpCodeStr = (qp.qp_code || '').toUpperCase();
    const qpStream = (qp.exam_type || (qp.filename_data && qp.filename_data.exam_type) || '').toUpperCase();

    const isSdeRoom = stream.includes('SDE') || stream.includes('EDE');
    const isSdeQp = qpCodeStr.endsWith('A') || qpStream.includes('SDE') || qpStream.includes('EDE');

    if (isSdeRoom && isSdeQp) {
      score += 10;
      reasons.push('Stream match (SDE/EDE)');
    } else if (!isSdeRoom && !isSdeQp) {
      score += 10;
      reasons.push('Stream match (Regular)');
    } else if (isSdeRoom && !isSdeQp) {
      score -= 25;
      reasons.push('Stream mismatch (Room is SDE/EDE, QP is Regular)');
    } else if (!isSdeRoom && isSdeQp) {
      score -= 30;
      reasons.push('Stream mismatch (Room is Regular, QP is SDE)');
    }

    // 4. Syllabus Year match
    const yrCheck = isYearCompatible(roomParsed.syllabus_year, qp.syllabus_year);
    if (yrCheck.compatible) {
      score += 15;
      reasons.push(yrCheck.reason);
    } else {
      score -= 35;
      reasons.push(yrCheck.reason);
    }

    // 5. Date match
    const rDate = roomEntry.date;
    const qDate = qp.exam_date || (qp.filename_data && qp.filename_data.exam_date);
    if (rDate && qDate) {
      const rDClean = String(rDate).replace(/20(\d\d)/, '$1');
      const qDClean = String(qDate).replace(/20(\d\d)/, '$1');
      if (rDClean === qDClean) {
        score += 10;
        reasons.push(`Date match (${rDate})`);
      }
    }

    // Strict validation
    let isValidMatch = false;
    if (!titleMismatch && !differentElective) {
      if (codeMatched && yrCheck.compatible && score >= 80) {
        isValidMatch = true;
      } else if (titleMatched && yrCheck.compatible && score >= 80) {
        isValidMatch = true;
      }
    }

    return {
      score,
      status: isValidMatch ? 'EXACT' : 'UNMATCHED',
      reasons
    };
  }

  function matchAllRooms(seatingEntries, qpRecords, mode = 'strict') {
    const matchedEntries = [];
    const qpDistribution = {};

    let fullMatches = 0;
    let deptMatches = 0;
    let transMatches = 0;
    let alliedMatches = 0;
    let unmatchedCount = 0;
    let totalCandidates = 0;
    let totalScribes = 0;

    for (const r of (seatingEntries || [])) {
      const roomParsed = parseSeatingCourseName(r.courseName || '', r.pdfFileName || '');

      let bestScore = -999;
      let bestQp = null;
      let bestStatus = 'UNMATCHED';
      let bestReasons = [];

      for (const qp of (qpRecords || [])) {
        const result = scoreQpToSeating(r, qp, mode);
        if (result.score > bestScore) {
          bestScore = result.score;
          bestQp = qp;
          bestStatus = result.status;
          bestReasons = result.reasons;
        }
      }

      const entryCopy = Object.assign({}, r);
      const count = parseInt(r.count, 10) || 0;
      const scribes = parseInt(r.scribeCount, 10) || 0;
      totalCandidates += count;
      totalScribes += scribes;

      const isMatched = (bestStatus !== 'UNMATCHED') && (bestQp !== null);

      if (isMatched) {
        const matchedCode = bestQp.qp_code || '';
        entryCopy.qpCode = matchedCode;
        entryCopy.matchedQpCode = matchedCode;
        entryCopy.matchedPdfFilename = bestQp.filename || '';
        entryCopy.matchedCourseCode = bestQp.course_code || '';
        entryCopy.matchedCourseName = bestQp.course_name || '';
        entryCopy.matchStatus = bestStatus;
        entryCopy.matchScore = bestScore;
        entryCopy.matchReasons = bestReasons;

        if (bestStatus === 'EXACT') {
          fullMatches++;
        } else if (bestStatus === 'DEPT_ELECTIVE') {
          deptMatches++;
        } else if (bestStatus === 'TRANSLATION') {
          transMatches++;
        } else {
          alliedMatches++;
        }

        if (!qpDistribution[matchedCode]) {
          qpDistribution[matchedCode] = {
            qpCode: matchedCode,
            courseCode: bestQp.course_code || '',
            courseName: bestQp.course_name || '',
            pdfFilename: bestQp.filename || '',
            matchStatus: bestStatus,
            totalRequired: 0,
            totalScribes: 0,
            rooms: []
          };
        }
        const dist = qpDistribution[matchedCode];
        dist.totalRequired += count;
        dist.totalScribes += scribes;
        dist.rooms.push({
          roomSerial: r.roomSerial,
          roomName: `Room ${r.roomSerial}`,
          count,
          scribes,
          stream: r.stream || ''
        });
      } else {
        entryCopy.qpCode = '';
        entryCopy.matchedQpCode = '';
        entryCopy.matchedPdfFilename = '';
        entryCopy.matchStatus = 'UNMATCHED';
        entryCopy.matchScore = bestScore;

        const targetCode = (roomParsed.codes && roomParsed.codes[0]) || 'Unknown Code';
        const targetTitle = roomParsed.title || 'Unknown Title';
        if (bestReasons && bestReasons.some(reas => reas.includes('Different elective') || reas.includes('conflict'))) {
          const conflictReason = bestReasons.find(reas => reas.includes('Different elective') || reas.includes('conflict'));
          entryCopy.matchReasons = [`Requires ${targetCode} — ${targetTitle} (Mismatch: ${conflictReason})`];
        } else {
          entryCopy.matchReasons = [`QP Missing from batch (Requires ${targetCode} — ${targetTitle})`];
        }
        unmatchedCount++;
      }

      matchedEntries.push(entryCopy);
    }

    const qpDistList = Object.values(qpDistribution).sort((a, b) => String(a.qpCode).localeCompare(String(b.qpCode)));
    for (const item of qpDistList) {
      item.roomCount = item.rooms.length;
      item.roomsSummary = item.rooms.map(rm => `${rm.roomName} (${rm.count})`).join(', ');
    }

    const totalRooms = seatingEntries ? seatingEntries.length : 0;
    const totalMatched = fullMatches + deptMatches + transMatches + alliedMatches;

    const summary = {
      totalRooms,
      total_entries: totalRooms,
      totalCandidates,
      totalScribes,
      exactMatches: fullMatches,
      full_matches: fullMatches,
      deptElectiveMatches: deptMatches,
      translationMatches: transMatches,
      alliedMatches,
      partialMatches: deptMatches + transMatches + alliedMatches,
      unmatchedEntries: unmatchedCount,
      unmatched: unmatchedCount,
      uniqueQpsMatched: qpDistList.length,
      unique_qps_matched: qpDistList.length,
      match_rate_pct: totalRooms ? Math.round((totalMatched / totalRooms) * 1000) / 10 : 0.0,
      matchingMode: mode
    };

    const result = {
      matched_entries: matchedEntries,
      qp_distribution: qpDistList,
      summary: summary
    };

    // Support array destructuring [matchedEntries, qpDistList, stats]
    result[Symbol.iterator] = function* () {
      yield matchedEntries;
      yield qpDistList;
      yield summary;
    };

    return result;
  }

  function exportPopulatedSeatingJson(matchedEntries) {
    const exportList = (matchedEntries || []).map(m => ({
      stream: m.stream || 'Regular',
      date: m.date || '',
      time: m.time || '',
      roomSerial: m.roomSerial || 1,
      qpCode: m.qpCode || '',
      courseName: m.courseName || '',
      count: m.count || 0,
      scribeCount: m.scribeCount || 0,
      pdfFileName: m.pdfFileName || ''
    }));
    return JSON.stringify(exportList, null, 2);
  }

  return {
    normalizeCode,
    extractAllCodes,
    normalizeTitle,
    parseSeatingCourseName,
    isYearCompatible,
    scoreQpToSeating,
    matchAllRooms,
    exportPopulatedSeatingJson
  };
}));
