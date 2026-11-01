import os
import re

html_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\index.html"
with open(html_path, "r", encoding="utf-8") as f:
    html = f.read()

# 1. Update Header '<th>Syllabus</th>' in roomAllotmentTable to '<th>Exam Date</th>'
html = html.replace('<th>Syllabus</th>\n              <th>Candidates</th>', '<th>Exam Date</th>\n              <th>Candidates</th>')

# 2. Update parseFilenameMetadata and extractFromPdfFile
new_extractor_code = """// Extract Metadata from Filename (Underscore delimited & standard forms)
    function parseFilenameMetadata(filename) {
      if (!filename) return {};
      const cleanName = filename.replace(/\\.pdf$/i, '').trim();
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

        // Skip numeric IDs / epoch timestamps e.g. 1793342140511
        if (/^\\d{6,}$/.test(pt)) continue;

        if (/^(REG|REGULAR)$/i.test(pt)) {
          exam_type = 'Regular';
        } else if (/^(SDE|EDE|PRIVATE)$/i.test(pt)) {
          exam_type = 'SDE';
        } else if (/^\\d{2}\\.\\d{2}\\.\\d{2,4}$/.test(pt)) {
          exam_date = pt;
        } else if (/^\\d{1,2}[\\:\\_\\-]\\d{2}\\s*(AM|PM)?$/i.test(pt) || /^(FN|AN)$/i.test(pt)) {
          exam_time = pt.replace('_', ':');
        } else {
          const mQp = pt.match(/^([A-Za-z])?(\\d{5,6})([A-Za-z])?$/);
          if (mQp) {
            const letter = mQp[1] ? mQp[1].toUpperCase() : 'D';
            const digits = mQp[2];
            let suffix = mQp[3] ? mQp[3].toUpperCase() : '';
            if (!suffix && (/SDE/i.test(cleanName) || new RegExp(digits + 'A', 'i').test(cleanName))) {
              suffix = 'A';
            }
            qp_code = `${letter} ${digits}${suffix}`;
          } else {
            // Course title and code segment
            let segment = pt;
            const parMatches = Array.from(segment.matchAll(/\\((.*?)\\)/g));
            if (parMatches.length > 0) {
              course_code = parMatches[parMatches.length - 1][1].trim();
              course_name = segment.replace(/\\(.*?\\)/g, '').replace(/[\\-\\u2010-\\u2015\\u2212\\xad]+$/, '').trim();
            } else if (!/^\\d+$/.test(segment) && segment.length > 2) {
              course_name = segment;
            }
          }
        }
      }

      return {
        exam_type,
        exam_date,
        exam_time,
        qp_code,
        course_name,
        course_code,
        raw_parts: parts
      };
    }

    // Pure Client-Side PDF Parser with Local OCR Fallback
    async function extractFromPdfFile(file) {
      const arrayBuffer = await file.arrayBuffer();
      const uint8 = new Uint8Array(arrayBuffer);
      let doc;
      try {
        doc = await pdfjsLib.getDocument({ data: uint8, isEvalSupported: false }).promise;
      } catch (err) {
        return {
          filename: file.name,
          error: err.message,
          qp_code: null,
          course_name: null,
          course_code: null,
          syllabus_year: null
        };
      }

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
          if (!suffix && file.name && (/SDE/i.test(file.name) || new RegExp(digits + 'A', 'i').test(file.name))) {
            suffix = 'A';
          }
          qp_code = `${letter} ${digits}${suffix}`;
          break;
        }
      }
      if (!qp_code && file.name) {
        const m = file.name.match(/(?:^|[_\\-\\s])([A-Za-z])?(\\d{5,6})([A-Za-z])?(?:[_\\-\\s]|$)/);
        if (m) {
          const letter = m[1] ? m[1].toUpperCase() : 'D';
          const digits = m[2];
          let suffix = m[3] ? m[3].toUpperCase() : '';
          if (!suffix && (/SDE/i.test(file.name) || new RegExp(digits + 'A', 'i').test(file.name))) {
            suffix = 'A';
          }
          qp_code = `${letter} ${digits}${suffix}`;
        }
      }

      // 2. Syllabus Year
      let syllabus_year = null;
      let syllabus_text = null;
      for (const l of lines) {
        const mSyl = l.match(/(?:(20\\d\\d)\\s*[\\-\\u2010-\\u2015\\u2212\\xad]\\s*(20\\d\\d)\\s*(?:Admissions?|Syllabus)?|\\((20\\d\\d)\\s*Admission\\s*Onwards?\\)|(?:Syllabus\\s*Year\\s*[:\\-]?\\s*|(?:^|[^\\d])\\(?)(20\\d\\d)(?:\\s*Syllabus|\\s*Admissions?\\)?))/i);
        if (mSyl) {
          syllabus_year = mSyl[1] ? `${mSyl[1]}-${mSyl[2]}` : (mSyl[3] || mSyl[4]);
          syllabus_text = l;
          break;
        }
      }
      if (!syllabus_year) {
        for (const l of lines) {
          const m = l.match(/\\b(201\\d|202\\d)\\b/);
          if (m && !l.includes('EXAMINATION')) {
            syllabus_year = m[1];
            syllabus_text = l;
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
        if (/DEGREE EXAMINATION|NOVEMBER|PAGES|REGISTER|TURN OVER|TIME\\s*:|MAXIMUM|NAME\\b/i.test(l)) continue;

        // Pattern A: Code + Delimiter + Title (Delimiter = dash, 2+ spaces, or space before uppercase / Indic characters)
        const mCompound = l.match(/^((?:[A-Za-z]{2,4}\\s*\\d[A-Za-z0-9\\(\\)\\.\\s\\-\\u2010-\\u2015\\u2212]*?(?:\\s*[\\/]\\s*[A-Za-z]{2,4}\\s*\\d[A-Za-z0-9\\(\\)\\.\\s\\-\\u2010-\\u2015\\u2212]*?)*))(?:\s*[\\-\\u2010-\\u2015\\u2212\\xad]+\\s*|\\s{2,}|\\s+(?=[A-Z\\u0D00-\\u0D7F\\u0B80-\\u0BFF]))(.+)$/);
        if (mCompound && /[A-Za-z]{2,4}\\s*\\d/i.test(mCompound[1])) {
          const cCand = mCompound[1].trim();
          const tCand = mCompound[2].trim();
          if (cCand.length <= 40 && tCand.length >= 3 && !/^(Section|Part|Time|Marks)/i.test(tCand)) {
            course_code = cCand;
            course_name = tCand;
            full_title = l;
            break;
          }
        }

        // Pattern B: Code on line i, Title on line i+1 (e.g. MAT5EJ301(1) \\n Mathematical Foundations...)
        const mCodeOnly = l.match(/^([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9\\(\\)\\/\\.\\s\\-\\u2010-\\u2015\\u2212]+)$/);
        if (mCodeOnly && i + 1 < lines.length) {
          const nextL = lines[i + 1].trim();
          if (nextL.length >= 4 && !/ADMISSION|SEMESTER|EXAMINATION|MAXIMUM|TIME|SECTION|PART\\b/i.test(nextL)) {
            course_code = mCodeOnly[1].trim();
            course_name = nextL;
            full_title = `${course_code} - ${course_name}`;
            break;
          }
        }
      }

      // 4. Scanned PDF fallback to Local OCR Backend (for vector/image-based PDFs like 153668)
      if ((!course_name || lines.length < 5) && typeof fetch !== 'undefined') {
        try {
          const formData = new FormData();
          formData.append('file', file);
          const backendUrl = (window.location.protocol.startsWith('http')) ? '/api/extract' : 'http://localhost:5050/api/extract';
          const ocrResp = await fetch(backendUrl, { method: 'POST', body: formData });
          if (ocrResp.ok) {
            const ocrList = await ocrResp.json();
            if (Array.isArray(ocrList) && ocrList.length > 0 && ocrList[0].course_name) {
              const ocr = ocrList[0];
              if (ocr.qp_code) qp_code = ocr.qp_code;
              if (ocr.course_code) course_code = ocr.course_code;
              if (ocr.course_name) course_name = ocr.course_name;
              if (ocr.syllabus_year) syllabus_year = ocr.syllabus_year;
              if (ocr.full_course_title) full_title = ocr.full_course_title;
            }
          }
        } catch (e) {
          // Local backend offline, gracefully proceed
        }
      }

      const fn_meta = parseFilenameMetadata(file.name);
      if (!course_code && fn_meta.course_code) course_code = fn_meta.course_code;
      if (!course_name && fn_meta.course_name && !/^\\d+$/.test(fn_meta.course_name)) course_name = fn_meta.course_name;
      if (!qp_code && fn_meta.qp_code) qp_code = fn_meta.qp_code;

      return {
        filename: file.name,
        qp_code,
        course_name,
        course_code,
        full_course_title: full_title || `${course_code || ''} - ${course_name || ''}`,
        syllabus_year,
        syllabus_text,
        exam_type: fn_meta.exam_type,
        exam_date: fn_meta.exam_date,
        exam_time: fn_meta.exam_time,
        filename_qp_code: fn_meta.qp_code,
        filename_course_name: fn_meta.course_name,
        filename_course_code: fn_meta.course_code,
        filename_raw_parts: fn_meta.raw_parts,
        filename_data: fn_meta
      };
    }"""

idx1 = html.find('// Extract Metadata from Filename')
idx2 = html.find('// Extract & Match Handler')
if idx1 != -1 and idx2 != -1:
    html = html[:idx1] + new_extractor_code + "\n\n    " + html[idx2:]
    print("Updated parseFilenameMetadata and extractFromPdfFile successfully.")
else:
    print("ERROR: could not find indices for extractor code.")

# 3. Update btnExtract matching call
old_match_call = """      // Run Room Matcher if Seating JSON is attached
      if (attachedSeatingJson && attachedSeatingJson.entries) {
        if (typeof QPMatcher !== 'undefined') {
          currentRoomMatching = QPMatcher.matchAllRooms(attachedSeatingJson.entries, currentQpData);
          showToast(`Extracted ${results.length} QPs & matched ${currentRoomMatching.summary.totalRooms} rooms!`);
          renderResults(currentQpData);
          switchTableView('rooms');
        }
      } else {"""

new_match_call = """      // Run Room Matcher if Seating JSON is attached
      if (attachedSeatingJson && attachedSeatingJson.entries) {
        const engine = (typeof Matcher !== 'undefined') ? Matcher : (typeof QPMatcher !== 'undefined' ? QPMatcher : null);
        if (engine && engine.matchAllRooms) {
          currentRoomMatching = engine.matchAllRooms(attachedSeatingJson.entries, currentQpData);
          showToast(`Extracted ${results.length} QPs & matched ${currentRoomMatching.summary.exactMatches} rooms with 100% confidence!`);
          renderResults(currentQpData);
          switchTableView('rooms');
        }
      } else {"""

html = html.replace(old_match_call, new_match_call)

# 4. Update renderStandardTableRows to match view columns
new_render_standard_rows = """function renderStandardTableRows() {
      const tbody = document.getElementById('resultsTableBody');
      const query = searchInput.value.toLowerCase().trim();
      const year = yearFilter.value;

      let filtered = currentQpData.filter(item => {
        const matchesQuery = !query ||
          (item.qp_code && item.qp_code.toLowerCase().includes(query)) ||
          (item.course_name && item.course_name.toLowerCase().includes(query)) ||
          (item.course_code && item.course_code.toLowerCase().includes(query)) ||
          (item.filename && item.filename.toLowerCase().includes(query));
        const matchesYear = !year || item.syllabus_year === year;
        return matchesQuery && matchesYear;
      });

      filtered.sort((a, b) => {
        let vA = a[currentSort.col] || '';
        let vB = b[currentSort.col] || '';
        return currentSort.asc ? String(vA).localeCompare(String(vB)) : String(vB).localeCompare(String(vA));
      });

      if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="10" style="text-align:center; padding:30px; color:#94a3b8;">No records match your filter</td></tr>`;
        return;
      }

      if (currentView === 'pdf') {
        tbody.innerHTML = filtered.map((row, idx) => `
          <tr>
            <td style="color:#94a3b8; font-weight:500;">${idx + 1}</td>
            <td>
              ${row.qp_code ? `<span class="pill pill-qp" style="cursor:pointer;" onclick="copyText('${row.qp_code}')">${escapeHtml(row.qp_code)}</span>` : '<span style="color:#ef4444; font-weight:600;">Not found</span>'}
            </td>
            <td style="font-weight:600; color:#0f172a;">${escapeHtml(row.course_name || '—')}</td>
            <td><span class="pill pill-code">${escapeHtml(row.course_code || 'N/A')}</span></td>
            <td><span class="pill pill-year">${escapeHtml(row.syllabus_year || 'N/A')}</span></td>
            <td style="font-family:'JetBrains Mono',monospace; font-size:11px; color:#64748b;" title="${escapeHtml(row.filename)}">
              ${escapeHtml(row.filename && row.filename.length > 25 ? row.filename.slice(0, 22) + '...' : (row.filename || '—'))}
            </td>
            <td style="text-align:center;">
              <button class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="viewDetails(${escapeHtml(JSON.stringify(row))})">
                Details
              </button>
            </td>
          </tr>
        `).join('');
      } else if (currentView === 'filename') {
        tbody.innerHTML = filtered.map((row, idx) => `
          <tr>
            <td style="color:#94a3b8; font-weight:500;">${idx + 1}</td>
            <td>${row.exam_type ? `<span class="pill ${row.exam_type === 'SDE' ? 'pill-sde' : 'pill-reg'}">${escapeHtml(row.exam_type)}</span>` : '—'}</td>
            <td><strong>${escapeHtml(row.exam_date || '—')}</strong></td>
            <td>${escapeHtml(row.exam_time || '—')}</td>
            <td>${row.qp_code ? `<span class="pill pill-qp">${escapeHtml(row.qp_code)}</span>` : '—'}</td>
            <td><span class="pill pill-code">${escapeHtml(row.course_code || 'N/A')}</span></td>
            <td style="font-weight:600; color:#0f172a;">${escapeHtml(row.course_name || '—')}</td>
            <td style="font-size:11px; color:#64748b;">${escapeHtml((row.filename_raw_parts || []).join(' | '))}</td>
            <td style="font-family:'JetBrains Mono',monospace; font-size:11px; color:#64748b;">${escapeHtml(row.filename || '—')}</td>
            <td style="text-align:center;">
              <button class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="viewDetails(${escapeHtml(JSON.stringify(row))})">
                Details
              </button>
            </td>
          </tr>
        `).join('');
      } else { // combined
        tbody.innerHTML = filtered.map((row, idx) => `
          <tr>
            <td style="color:#94a3b8; font-weight:500;">${idx + 1}</td>
            <td>
              ${row.qp_code ? `<span class="pill pill-qp" style="cursor:pointer;" onclick="copyText('${row.qp_code}')">${escapeHtml(row.qp_code)}</span>` : '<span style="color:#ef4444; font-weight:600;">Not found</span>'}
            </td>
            <td style="font-weight:600; color:#0f172a;">${escapeHtml(row.course_name || '—')}</td>
            <td><span class="pill pill-code">${escapeHtml(row.course_code || 'N/A')}</span></td>
            <td><span class="pill pill-year">${escapeHtml(row.syllabus_year || 'N/A')}</span></td>
            <td>${row.exam_type ? `<span class="pill ${row.exam_type === 'SDE' ? 'pill-sde' : 'pill-reg'}">${escapeHtml(row.exam_type)}</span>` : '—'}</td>
            <td><strong>${escapeHtml(row.exam_date || '—')}</strong></td>
            <td>${escapeHtml(row.exam_time || '—')}</td>
            <td style="font-family:'JetBrains Mono',monospace; font-size:11px; color:#64748b;" title="${escapeHtml(row.filename)}">
              ${escapeHtml(row.filename && row.filename.length > 20 ? row.filename.slice(0, 18) + '...' : (row.filename || '—'))}
            </td>
            <td style="text-align:center;">
              <button class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="viewDetails(${escapeHtml(JSON.stringify(row))})">
                Details
              </button>
            </td>
          </tr>
        `).join('');
      }
    }"""

idx_r_start = html.find('function renderStandardTableRows() {')
idx_r_end = html.find('// Render Room Allotment Table', idx_r_start)
if idx_r_start != -1 and idx_r_end != -1:
    html = html[:idx_r_start] + new_render_standard_rows + "\n\n    " + html[idx_r_end:]
    print("Updated renderStandardTableRows successfully.")
else:
    print("ERROR: could not find renderStandardTableRows bounds.")

# 5. Update renderRoomAllotmentTable
new_render_room_table = """function renderRoomAllotmentTable() {
      const tbody = document.getElementById('roomTableBody');
      if (!currentRoomMatching || !currentRoomMatching.matched_entries) {
        tbody.innerHTML = `<tr><td colspan="10" style="text-align:center; padding:40px; color:#64748b;">No Seating Summary attached. Drag &amp; drop a Room_Wise_Seating_Summary.json or click "Attach Seating JSON".</td></tr>`;
        return;
      }

      const query = searchInput.value.toLowerCase().trim();
      const streamVal = streamFilter.value;
      const statusVal = matchStatusFilter.value;

      let filtered = currentRoomMatching.matched_entries.filter(r => {
        const matchesQuery = !query ||
          String(r.roomSerial).includes(query) ||
          (r.courseName && r.courseName.toLowerCase().includes(query)) ||
          (r.matchedQpCode && r.matchedQpCode.toLowerCase().includes(query)) ||
          (r.matchedPdfFilename && r.matchedPdfFilename.toLowerCase().includes(query));

        const matchesStream = !streamVal ||
          (streamVal === 'SDE' ? (r.stream === 'SDE' || r.stream === 'EDE') : r.stream === streamVal);

        const isExact = (r.matchStatus === 'FULL_MATCH' || r.matchStatus === 'EXACT');
        const matchesStatus = !statusVal ||
          (statusVal === 'MATCHED' ? isExact : (statusVal === 'PARTIAL' ? r.matchStatus === 'PARTIAL' : (statusVal === 'UNMATCHED' ? !isExact : true)));

        return matchesQuery && matchesStream && matchesStatus;
      });

      if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="10" style="text-align:center; padding:30px; color:#94a3b8;">No room allotments match the filter</td></tr>`;
        return;
      }

      tbody.innerHTML = filtered.map((row, idx) => {
        const isMatched = (row.matchStatus === 'FULL_MATCH' || row.matchStatus === 'EXACT');
        let statusBadge = '<span class="badge-unmatched">❌ UNMATCHED</span>';
        if (isMatched) {
          statusBadge = `<span class="badge-exact" title="${escapeHtml((row.matchReasons || []).join(', '))}">✅ MATCHED (${row.matchScore} pts)</span>`;
        } else if (row.matchStatus === 'PARTIAL') {
          statusBadge = `<span class="badge-partial" title="${escapeHtml((row.matchReasons || []).join(', '))}">⚠️ PARTIAL (${row.matchScore} pts)</span>`;
        }

        const origIdx = row._origIndex !== undefined ? row._origIndex : idx;
        const qpOptions = (currentQpData || []).map(q => `
          <option value="${escapeHtml(q.qp_code)}" ${q.qp_code === row.matchedQpCode ? 'selected' : ''}>
            ${escapeHtml(q.qp_code)}: ${escapeHtml((q.course_code || q.course_name || '').slice(0, 18))}
          </option>
        `).join('');

        const qpDisplay = `
          <div style="display:flex; flex-direction:column; gap:4px;">
            <div>
              ${row.matchedQpCode
                ? `<span class="pill pill-qp" style="cursor:pointer;" onclick="copyText('${row.matchedQpCode}')">${escapeHtml(row.matchedQpCode)}</span>`
                : `<span style="color:#ef4444; font-weight:600;">—</span>`}
            </div>
            <select style="font-size:11px; padding:2px 4px; border:1px solid var(--slate-300); border-radius:4px; max-width:140px; background:white;" onchange="manualReassignQp(${origIdx}, this.value)" title="Change or assign QP for Room ${row.roomSerial}">
              <option value="">${row.matchedQpCode ? 'Change QP...' : 'Assign QP...'}</option>
              ${qpOptions}
            </select>
          </div>
        `;

        const reasonMsg = (row.matchReasons && row.matchReasons.length > 0) ? row.matchReasons[0] : '';

        return `
          <tr>
            <td style="color:#94a3b8; font-weight:500;">${idx + 1}</td>
            <td><span class="badge-room">Room ${escapeHtml(row.roomSerial)}</span></td>
            <td><span class="pill ${row.stream === 'EDE' || row.stream === 'SDE' ? 'pill-sde' : 'pill-reg'}">${escapeHtml(row.stream || 'Regular')}</span></td>
            <td>${qpDisplay}</td>
            <td style="max-width:320px;">
              <div style="font-weight:600; color:#0f172a;">${escapeHtml(row.courseName)}</div>
              ${isMatched && row.matchedCourseName ? `<div style="font-size:11px; color:#059669; margin-top:2px; font-weight:500;">✓ Matched: ${escapeHtml(row.matchedCourseCode || '')} — ${escapeHtml(row.matchedCourseName)}</div>` : ''}
              ${!isMatched ? `<div style="font-size:11px; color:#dc2626; margin-top:2px; font-weight:500;">⚠️ ${escapeHtml(reasonMsg || 'QP not in uploaded folder')}</div>` : ''}
            </td>
            <td><span class="pill pill-year">${escapeHtml(row.date || '—')}</span></td>
            <td>
              <strong>${row.count}</strong> QPs
              ${row.scribeCount > 0 ? `<span style="font-size:11px; color:#059669;"> (+${row.scribeCount} scribes)</span>` : ''}
            </td>
            <td style="font-family:'JetBrains Mono',monospace; font-size:11px; color:#64748b;" title="${escapeHtml(row.matchedPdfFilename || 'None')}">
              ${escapeHtml(row.matchedPdfFilename ? (row.matchedPdfFilename.length > 20 ? row.matchedPdfFilename.slice(0, 18) + '...' : row.matchedPdfFilename) : '—')}
            </td>
            <td>${statusBadge}</td>
            <td style="text-align:center;">
              <button class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="viewRoomDetails(${escapeHtml(JSON.stringify(row))})">
                Details
              </button>
            </td>
          </tr>
        `;
      }).join('');
    }"""

idx_rm_start = html.find('function renderRoomAllotmentTable() {')
idx_rm_end = html.find('// Manual Reassignment Handler', idx_rm_start)
if idx_rm_start != -1 and idx_rm_end != -1:
    html = html[:idx_rm_start] + new_render_room_table + "\n\n    " + html[idx_rm_end:]
    print("Updated renderRoomAllotmentTable successfully.")
else:
    print("ERROR: could not find renderRoomAllotmentTable bounds.")

# 6. Update recalculateQpDistribution
old_recalc = """      for (const r of currentRoomMatching.matched_entries) {
        if (r.matchStatus === 'EXACT') exact++;
        else if (r.matchStatus === 'PARTIAL') partial++;
        else unmatched++;"""

new_recalc = """      for (const r of currentRoomMatching.matched_entries) {
        if (r.matchStatus === 'FULL_MATCH' || r.matchStatus === 'EXACT') exact++;
        else if (r.matchStatus === 'PARTIAL') partial++;
        else unmatched++;"""

html = html.replace(old_recalc, new_recalc)

with open(html_path, "w", encoding="utf-8") as f:
    f.write(html)

print("Patch applied successfully to index.html!")

