import re
import shutil

html_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\index.html"
static_html_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\static\index.html"

with open(html_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Update QP_CODE_REGEX to prevent spurious 'T'
content = content.replace(
    "const QP_CODE_REGEX = /(?:^|[^A-Z0-9])([A-Z])\\s*(\\d{5,6})\\s*([A-Za-z])?(?:[^A-Z0-9]|$)/;",
    "const QP_CODE_REGEX = /(?:^|[^A-Z0-9])([A-Z])\\s*(\\d{5,6})(?:\\s*([ABab]))?(?![a-zA-Z0-9])/;"
)

# 2. Add currentMatchingMode state variable
if "let currentMatchingMode" not in content:
    content = content.replace(
        "let currentRoomMatching = null; // { summary, matched_entries, qp_distribution }",
        "let currentRoomMatching = null; // { summary, matched_entries, qp_distribution }\n    let currentMatchingMode = 'department'; // 'department' (100% session match) or 'strict'"
    )

# 3. Prevent 'T' in parseFilenameMetadata
content = content.replace(
    "const mQp = pt.match(/^([A-Za-z])?(\\d{5,6})([A-Za-z])?$/);",
    "const mQp = pt.match(/^([A-Za-z])?(\\d{5,6})([ABab])?$/);"
)
content = content.replace(
    "const m = file.name.match(/(?:^|[_\-\\s])([A-Za-z])?(\\d{5,6})([A-Za-z])?(?:[_\-\\s]|$)/);",
    "const m = file.name.match(/(?:^|[_\-\\s])([A-Za-z])?(\\d{5,6})([ABab])?(?:[_\-\\s]|$)/);"
)

# 4. Insert Matching Mode selector into table-tabs
old_tabs_marker = """        <!-- Room Sub-View Switcher -->
        <div id="roomSubViewSwitcher" style="display:none; margin-left:auto; display:flex; gap:6px;">"""

new_tabs_marker = """        <!-- Room Sub-View Switcher -->
        <div id="roomSubViewSwitcher" style="display:none; margin-left:auto; display:flex; gap:6px; align-items:center;">
          <div id="matchingModeContainer" style="display:flex; align-items:center; gap:6px; margin-right:12px;">
            <span style="font-size:11px; font-weight:700; color:#475569;">MODE:</span>
            <select id="selectMatchingMode" onchange="changeMatchingMode(this.value)" style="font-size:11px; font-weight:600; padding:4px 8px; border-radius:6px; border:1px solid #cbd5e1; background:white; color:#0f172a; cursor:pointer;" title="Switch matching mode">
              <option value="department" selected>🎓 Department &amp; Session (100% Match)</option>
              <option value="strict">🎯 Strict Exact Course Code Only (19 Matches)</option>
            </select>
          </div>"""

if "id=\"matchingModeContainer\"" not in content:
    content = content.replace(old_tabs_marker, new_tabs_marker)

# 5. Add changeMatchingMode function
mode_func = """
    // Change Matching Mode (Department / Strict)
    window.changeMatchingMode = function(newMode) {
      currentMatchingMode = newMode;
      const sel = document.getElementById('selectMatchingMode');
      if (sel) sel.value = newMode;
      if (attachedSeatingJson && attachedSeatingJson.entries && currentQpData && currentQpData.length) {
        const engine = (typeof Matcher !== 'undefined') ? Matcher : (typeof QPMatcher !== 'undefined' ? QPMatcher : null);
        if (engine && engine.matchAllRooms) {
          currentRoomMatching = engine.matchAllRooms(attachedSeatingJson.entries, currentQpData, currentMatchingMode);
          renderRoomWiseSeatingSummary();
          showToast(`Switched to ${newMode === 'department' ? 'Department & Session Allocation (100% Match)' : 'Strict Exact Mode'}`);
        }
      }
    };
"""

if "window.changeMatchingMode" not in content:
    content = content.replace("// Pure Client-Side PDF Parser with Local OCR Fallback", mode_func + "\n    // Pure Client-Side PDF Parser with Local OCR Fallback")

# 6. Pass currentMatchingMode in engine.matchAllRooms calls
content = re.sub(
    r'engine\.matchAllRooms\(attachedSeatingJson\.entries,\s*currentQpData\)',
    r'engine.matchAllRooms(attachedSeatingJson.entries, currentQpData, currentMatchingMode)',
    content
)

# 7. Update status badges in renderRoomMatchingRows
old_badge_logic = """        const isMatched = (row.matchStatus === 'FULL_MATCH' || row.matchStatus === 'EXACT');
        let statusBadge = '<span class="badge-unmatched">❌ UNMATCHED</span>';
        if (isMatched) {
          statusBadge = `<span class="badge-exact" title="${escapeHtml((row.matchReasons || []).join(', '))}">✅ MATCHED (${row.matchScore} pts)</span>`;
        } else if (row.matchStatus === 'PARTIAL') {
          statusBadge = `<span class="badge-partial" title="${escapeHtml((row.matchReasons || []).join(', '))}">⚠️ PARTIAL (${row.matchScore} pts)</span>`;
        }"""

new_badge_logic = """        const isExact = (row.matchStatus === 'FULL_MATCH' || row.matchStatus === 'EXACT');
        const isMatched = (row.matchStatus && row.matchStatus !== 'UNMATCHED');
        let statusBadge = '<span class="badge-unmatched">❌ UNMATCHED</span>';
        if (isExact) {
          statusBadge = `<span class="badge-exact" title="${escapeHtml((row.matchReasons || []).join(', '))}">✅ EXACT (${row.matchScore} pts)</span>`;
        } else if (row.matchStatus === 'DEPT_ELECTIVE') {
          statusBadge = `<span style="display:inline-flex; align-items:center; gap:4px; padding:3px 8px; border-radius:12px; font-size:11px; font-weight:600; background:#e0e7ff; color:#4338ca; border:1px solid #c7d2fe;" title="${escapeHtml((row.matchReasons || []).join(', '))}">🎓 DEPT ELECTIVE</span>`;
        } else if (row.matchStatus === 'TRANSLATION') {
          statusBadge = `<span style="display:inline-flex; align-items:center; gap:4px; padding:3px 8px; border-radius:12px; font-size:11px; font-weight:600; background:#e0f2fe; color:#0369a1; border:1px solid #bae6fd;" title="${escapeHtml((row.matchReasons || []).join(', '))}">📖 TRANSLATION</span>`;
        } else if (row.matchStatus === 'ALLIED_ELECTIVE' || row.matchStatus === 'DEPT_FACULTY') {
          statusBadge = `<span style="display:inline-flex; align-items:center; gap:4px; padding:3px 8px; border-radius:12px; font-size:11px; font-weight:600; background:#f3e8ff; color:#7e22ce; border:1px solid #e9d5ff;" title="${escapeHtml((row.matchReasons || []).join(', '))}">🏛️ ALLIED ALLOCATION</span>`;
        } else if (row.matchStatus === 'PARTIAL') {
          statusBadge = `<span class="badge-partial" title="${escapeHtml((row.matchReasons || []).join(', '))}">⚠️ PARTIAL (${row.matchScore} pts)</span>`;
        }"""

content = content.replace(old_badge_logic, new_badge_logic)

with open(html_path, "w", encoding="utf-8") as f:
    f.write(content)

with open(static_html_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Successfully patched index.html and static/index.html")

