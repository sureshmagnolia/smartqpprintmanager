html_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\index.html"
static_html_path = r"C:\Users\sures\.gemini\antigravity\scratch\qp_extractor\static\index.html"

with open(html_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Update default mode variable
content = content.replace(
    "let currentMatchingMode = 'department';",
    "let currentMatchingMode = 'strict';"
)

# 2. Update select dropdown HTML
old_select = """            <select id="selectMatchingMode" onchange="changeMatchingMode(this.value)" style="font-size:11px; font-weight:600; padding:4px 8px; border-radius:6px; border:1px solid #cbd5e1; background:white; color:#0f172a; cursor:pointer;" title="Switch matching mode">
              <option value="department" selected>🎓 Department &amp; Session (100% Match)</option>
              <option value="strict">🎯 Strict Exact Course Code Only (19 Matches)</option>
            </select>"""

new_select = """            <select id="selectMatchingMode" onchange="changeMatchingMode(this.value)" style="font-size:11px; font-weight:600; padding:4px 8px; border-radius:6px; border:1px solid #cbd5e1; background:white; color:#0f172a; cursor:pointer;" title="Switch matching mode">
              <option value="strict" selected>🎯 Strict 3-Key Verification (Code + Title + Year)</option>
              <option value="department">🎓 Department Elective Mapping (Experimental)</option>
            </select>"""

content = content.replace(old_select, new_select)

with open(html_path, "w", encoding="utf-8") as f:
    f.write(content)

with open(static_html_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Successfully updated index.html and static/index.html to default to strict 3-key matching mode.")

