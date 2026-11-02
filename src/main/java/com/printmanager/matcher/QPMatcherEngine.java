package com.printmanager.matcher;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust Question Paper & Seating Requirement Matching Engine.
 * Trained on real-world Calicut University exam datasets.
 * Matches PDFs to seating plan courses accurately with or without QP codes.
 */
public class QPMatcherEngine {
    private static final Logger logger = LoggerFactory.getLogger(QPMatcherEngine.class);

    private static final Pattern QP_CODE_REGEX = Pattern.compile("(?:^|[^A-Z0-9])([A-Z])\\s*(\\d{5,6})(?:\\s*([ABab]))?(?![a-zA-Z0-9])");
    private static final Pattern FN_QP_REGEX = Pattern.compile("(?:^|[\\s_\\-])([A-Za-z])?(\\d{5,6})([ABab])?(?:[\\s_\\-]|\\.pdf|\\.PDF|$)");
    private static final Pattern YEAR_RANGE_REGEX = Pattern.compile("\\b(20\\d\\d)\\s*[-–—]\\s*(20\\d\\d)\\b");
    private static final Pattern YEAR_ADMISSION_REGEX = Pattern.compile("\\((20\\d\\d)\\s*(?:Admission|Syllabus|Scheme|Admn)\\b|\\b(20\\d\\d)\\s*(?:Syllabus|Scheme|Admission|Admn)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_STANDALONE_REGEX = Pattern.compile("\\b(201\\d|202\\d)\\b");
    private static final Pattern VERNACULAR_SCRIPT_REGEX = Pattern.compile("[\\u0D00-\\u0D7F\\u0B80-\\u0BFF\\u0900-\\u097F\\u0600-\\u06FF]");

    public static class QPRecord {
        public File file;
        public String filename;
        public String qpCode;
        public String courseCode;
        public String courseName;
        public String fullCourseTitle;
        public String syllabusYear;
        public String stream; // Regular, SDE, EDE
        public List<String> extractedCodes = new ArrayList<>();
        public List<String> candidateTitles = new ArrayList<>();
        public int duplicateCount = 0;
        public List<String> duplicateFilenames = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("[%s] %s | %s (%s)", qpCode, courseCode, courseName, syllabusYear);
        }
    }

    public static class SeatingRequirement {
        public String originalCourseName;
        public String pdfFileName;
        public String stream;
        public String date;
        public String time;
        public String extractedCourseCode;
        public String normCourseCode;
        public String extractedTitle;
        public String normTitle;
        public String syllabusYear;
        public int totalCandidates;
        public int totalScribes;
        public List<Integer> roomSerials = new ArrayList<>();
        public List<JsonNode> matchingJsonNodes = new ArrayList<>();

        public String getDisplayName() {
            String streamTag = (stream != null && !stream.isEmpty()) ? "[" + stream + "] " : "";
            if (extractedCourseCode != null && !extractedCourseCode.isEmpty()) {
                return streamTag + extractedCourseCode + " — " + extractedTitle;
            }
            return streamTag + originalCourseName;
        }
    }

    public static class MatchDecision {
        public SeatingRequirement requirement;
        public QPRecord matchedRecord;
        public String status = "UNMATCHED"; // EXACT, MANUAL, UNMATCHED
        public int score = 0;
        public List<String> reasons = new ArrayList<>();
        public boolean isManualOverride = false;

        public String getMatchedQpCode() {
            return (matchedRecord != null && matchedRecord.qpCode != null) ? matchedRecord.qpCode : "";
        }

        public String getMatchedFileName() {
            return (matchedRecord != null && matchedRecord.file != null) ? matchedRecord.file.getName() : "";
        }

        @Override
        public String toString() {
            if (requirement == null) return "";
            return requirement.getDisplayName();
        }
    }

    // =========================================================================
    // METADATA EXTRACTION FROM PDF
    // =========================================================================

    public QPRecord extractFromPdf(File file) {
        QPRecord record = new QPRecord();
        record.file = file;
        record.filename = file.getName();

        List<String> lines = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            if (doc.getNumberOfPages() > 0) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                String text = stripper.getText(doc);
                for (String raw : text.split("\\r?\\n")) {
                    String clean = raw.trim();
                    if (!clean.isEmpty()) {
                        // Standardize unicode dashes and replacement chars
                        clean = clean.replaceAll("[\\u2013\\u2014\\u2212\\ufffd–—]+", "-");
                        lines.add(clean);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("PDFBox text extraction failed for {}: {}", file.getName(), e.getMessage());
        }

        // 1. QP Code from text
        for (int i = 0; i < Math.min(lines.size(), 15); i++) {
            Matcher m = QP_CODE_REGEX.matcher(lines.get(i));
            if (m.find()) {
                String letter = m.group(1).toUpperCase();
                String digits = m.group(2);
                String suffix = m.group(3) != null ? m.group(3).toUpperCase() : "";
                if (suffix.isEmpty() && (file.getName().toUpperCase().contains("SDE") || 
                        file.getName().matches(".*" + digits + "A.*"))) {
                    suffix = "A";
                }
                record.qpCode = letter + " " + digits + suffix;
                break;
            }
        }
        if (record.qpCode == null) {
            Matcher m = FN_QP_REGEX.matcher(file.getName());
            if (m.find()) {
                String letter = m.group(1) != null ? m.group(1).toUpperCase() : "D";
                String digits = m.group(2);
                String suffix = m.group(3) != null ? m.group(3).toUpperCase() : "";
                if (suffix.isEmpty() && (file.getName().toUpperCase().contains("SDE") || 
                        file.getName().matches(".*" + digits + "A.*"))) {
                    suffix = "A";
                }
                record.qpCode = letter + " " + digits + suffix;
            }
        }

        // 2. Syllabus Year from text
        for (String l : lines) {
            Matcher mRange = YEAR_RANGE_REGEX.matcher(l);
            if (mRange.find()) {
                record.syllabusYear = mRange.group(1);
                break;
            }
            Matcher mAdm = YEAR_ADMISSION_REGEX.matcher(l);
            if (mAdm.find()) {
                record.syllabusYear = (mAdm.group(1) != null) ? mAdm.group(1) : mAdm.group(2);
                break;
            }
        }
        if (record.syllabusYear == null) {
            for (String l : lines) {
                String upper = l.toUpperCase();
                if (!upper.contains("EXAMINATION") && !upper.matches("(?i).*(NOVEMBER|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|DECEMBER|JANUARY|FEBRUARY|MARCH)\\s*20\\d\\d.*")) {
                    Matcher mYr = YEAR_STANDALONE_REGEX.matcher(l);
                    if (mYr.find()) {
                        record.syllabusYear = mYr.group(1);
                        break;
                    }
                }
            }
        }

        // 2b. Stream Detection: Filename takes priority for portal downloads (e.g. SDE_ vs REG_)
        String fnUpper = file.getName().toUpperCase();
        if (fnUpper.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*") || 
            fnUpper.matches(".*(?:^|[\\s_\\-.])(EDE|SDE|DISTANCE|PRIVATE)(?:$|[\\s_\\-.]).*") ||
            fnUpper.matches(".*(?:^|[\\s_\\-])[A-Za-z]?\\d{5,6}A(?:[\\s_\\-]|\\.pdf|$).*") ||
            fnUpper.matches(".*(?:^|[\\s_\\-])A(?:[\\s_\\-]|\\.pdf|$).*")) {
            record.stream = "EDE";
        } else if (fnUpper.matches(".*\\b(REG|REGULAR)\\b.*") || fnUpper.matches(".*(?:^|[\\s_\\-.])(REG|REGULAR)(?:$|[\\s_\\-.]).*")) {
            record.stream = "Regular";
        }

        if (record.stream == null) {
            for (String l : lines) {
                String u = l.toUpperCase();
                if (u.matches(".*\\b(DISTANCE\\s+EDUCATION|SCHOOL\\s+OF\\s+DISTANCE|SDE|EDE|PRIVATE\\s+REGISTRATION|PRIVATE\\s+STUDY|PRIVATE\\s+CANDIDATES)\\b.*")) {
                    record.stream = "EDE";
                    break;
                }
            }
        }
        if (record.stream == null) {
            for (String l : lines) {
                String u = l.toUpperCase();
                if (u.contains("CUFYUGP") || u.matches(".*\\b(REGULAR|AFFILIATED\\s+COLLEGES|COLLEGE\\s+STUDY)\\b.*")) {
                    record.stream = "Regular";
                    break;
                }
            }
        }

        // 3. Robust Course Code & Title Extraction from text body
        String[] DEPT_NAMES = {
            "ACCOUNTING AND FINANCE", "COMPUTER SCIENCE", "ELECTRONICS", "APPLIED PHYSICS", "COMMUNICATION",
            "PHILOSOPHY", "MATHEMATICS", "CHEMISTRY", "BOTANY", "COMMERCE", "ECONOMICS", 
            "HISTORY", "PHYSICS", "ZOOLOGY", "MALAYALAM", "ENGLISH", "HINDI", "SANSKRIT", 
            "TAMIL", "GEOGRAPHY"
        };
        Pattern pCodePrefix = Pattern.compile("^[A-Za-z]{2,4}\\s*\\d");
        Pattern pDelimHeader = Pattern.compile("^([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_()./\\s–—\\-]*?)\\s*[-–—:]+\\s*([A-Za-z\\u0D00-\\u0D7F].+)$");

        for (int i = 0; i < Math.min(lines.size(), 25); i++) {
            String rawL = lines.get(i).trim();
            if (rawL.matches("(?i).*(DEGREE EXAMINATION|NOVEMBER|APRIL|PAGES|REGISTER|TURN OVER|TIME\\s*:|MAXIMUM|NAME\\b|MARKS\\b).*")) {
                continue;
            }

            String cleanL = rawL;
            String upperClean = cleanL.toUpperCase();
            for (String dept : DEPT_NAMES) {
                if (upperClean.startsWith(dept)) {
                    String rem = cleanL.substring(dept.length()).trim();
                    if (pCodePrefix.matcher(rem).find()) {
                        cleanL = rem;
                        break;
                    }
                }
            }

            if (!pCodePrefix.matcher(cleanL).find()) {
                continue;
            }

            // Approach A: Delimited by dash / colon
            Matcher mDelim = pDelimHeader.matcher(cleanL);
            if (mDelim.find()) {
                String cCand = mDelim.group(1).trim();
                String tCand = mDelim.group(2).trim();
                if (tCand.length() >= 2 && !tCand.matches("(?i)^(Section|Part|Time|Marks|Maximum|All Questions).*")) {
                    record.courseCode = cCand;
                    record.courseName = tCand;
                    record.fullCourseTitle = cleanL;
                    break;
                }
            }

            // Approach B: Multi-line (Code on line i, Title on line i+1)
            String cleanCodeOnly = cleanL.replaceAll("[-–—: ]+$", "").trim();
            if (cleanCodeOnly.matches("^[A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_()./\\s–—\\-]*$") && i + 1 < lines.size()) {
                String nextL = lines.get(i + 1).trim();
                if (nextL.length() >= 3 && !nextL.matches("(?i).*(ADMISSION|SEMESTER|EXAMINATION|MAXIMUM|TIME|SECTION|PART|TOTAL PAGES|NAME\\b|REGISTER).*")) {
                    record.courseCode = cleanCodeOnly;
                    record.courseName = nextL;
                    record.fullCourseTitle = record.courseCode + " - " + record.courseName;
                    break;
                }
            }
        }

        // 4. Filename metadata extraction (complements body text)
        parseFilenameFallback(file.getName(), record);

        // 5. OCR Fallback for vector or scanned PDFs if code or name is still missing
        if (record.courseCode == null || record.courseName == null || record.courseCode.isEmpty()) {
            tryOcrFallback(file, record);
        }

        // Compile extracted candidate codes and titles
        if (record.courseCode != null && !record.courseCode.trim().isEmpty()) {
            record.extractedCodes.add(record.courseCode.trim());
            record.extractedCodes.addAll(extractAllCodes(record.courseCode));
        }
        if (record.courseName != null && !record.courseName.trim().isEmpty()) {
            record.candidateTitles.add(normalizeTitle(record.courseName));
        }

        return record;
    }

    private void tryOcrFallback(File file, QPRecord record) {
        if (file == null || !file.exists()) return;
        try {
            File extractorDir = new File("qp_extractor");
            if (!extractorDir.exists()) {
                extractorDir = new File("D:\\smartqpprintmanager\\qp_extractor");
            }
            if (!extractorDir.exists()) return;

            String pyCmd = "import sys, json; sys.path.insert(0, r'" + extractorDir.getAbsolutePath().replace("\\", "\\\\") 
                + "'); from extractor import extract_qp_data; res = extract_qp_data(r'" 
                + file.getAbsolutePath().replace("\\", "\\\\") + "'); print(json.dumps(res))";
            
            ProcessBuilder pb = new ProcessBuilder("python", "-c", pyCmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            boolean finished = proc.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                logger.warn("OCR fallback timed out after 6 seconds for {}", file.getName());
                return;
            }
            int exitCode = proc.exitValue();
            if (exitCode == 0 && sb.length() > 0) {
                String out = sb.toString();
                int jsonStart = out.indexOf('{');
                int jsonEnd = out.lastIndexOf('}');
                if (jsonStart != -1 && jsonEnd > jsonStart) {
                    String jsonStr = out.substring(jsonStart, jsonEnd + 1);
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonStr);
                    if (record.courseCode == null && node.has("course_code") && !node.get("course_code").isNull()) {
                        record.courseCode = node.get("course_code").asText().trim();
                    }
                    if (record.courseName == null && node.has("course_name") && !node.get("course_name").isNull()) {
                        record.courseName = node.get("course_name").asText().trim();
                    }
                    if (record.syllabusYear == null && node.has("syllabus_year") && !node.get("syllabus_year").isNull()) {
                        record.syllabusYear = node.get("syllabus_year").asText().trim();
                    }
                    if ((record.qpCode == null || record.qpCode.isEmpty()) && node.has("qp_code") && !node.get("qp_code").isNull()) {
                        record.qpCode = node.get("qp_code").asText().trim();
                    }
                    if (record.courseCode != null && record.courseName != null) {
                        record.fullCourseTitle = record.courseCode + " — " + record.courseName;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("OCR extraction skipped/failed for {}: {}", file.getName(), e.getMessage());
        }
    }

    private void parseFilenameFallback(String filename, QPRecord record) {
        String clean = filename.replaceAll("(?i)\\.pdf$", "").trim();
        String upper = clean.toUpperCase();

        // 1. Stream from filename
        if (upper.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*") || 
            upper.matches(".*(?:^|[\\s_\\-.])(EDE|SDE|DISTANCE|PRIVATE)(?:$|[\\s_\\-.]).*") ||
            upper.matches(".*(?:^|[\\s_\\-])[A-Za-z]?\\d{5,6}A(?:[\\s_\\-]|\\.pdf|$).*") ||
            upper.matches(".*(?:^|[\\s_\\-])A(?:[\\s_\\-]|\\.pdf|$).*")) {
            if (record.stream == null) record.stream = "EDE";
        } else if (upper.matches(".*\\b(REG|REGULAR)\\b.*") || upper.matches(".*(?:^|[\\s_\\-.])(REG|REGULAR)(?:$|[\\s_\\-.]).*")) {
            if (record.stream == null) record.stream = "Regular";
        }

        // 2. Syllabus year from filename
        if (record.syllabusYear == null) {
            Matcher mYr = Pattern.compile("\\[(20\\d\\d)\\]|\\b(20\\d\\d)\\b").matcher(clean);
            if (mYr.find()) {
                record.syllabusYear = (mYr.group(1) != null) ? mYr.group(1) : mYr.group(2);
            }
        }

        // 3. Extract all parenthesized and delimited codes from filename
        // Matches e.g. (COM2CJ101), (ECO2 C05), (MAL2A08(1)), (5.5 BCH), (MAT2FM106 (2)), ____COM2CJ101_, HIS2CJ101
        Pattern pFnCode = Pattern.compile("--\\s*\\(?\\s*([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_./\\s–—\\-]*(?:\\([0-9A-Za-z]+\\)[A-Za-z0-9_./\\s–—\\-]*)*|\\d\\.\\d\\s*[A-Za-z0-9]+)\\s*\\)?|\\(\\s*([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_./\\s–—\\-]*(?:\\([0-9A-Za-z]+\\)[A-Za-z0-9_./\\s–—\\-]*)*|\\d\\.\\d\\s*[A-Za-z0-9]+)\\s*\\)");
        Matcher mFn = pFnCode.matcher(clean);
        while (mFn.find()) {
            String c = (mFn.group(1) != null) ? mFn.group(1).trim() : mFn.group(2).trim();
            String cu = c.toUpperCase();
            if (!cu.startsWith("CORE") && !cu.startsWith("MINOR") && !cu.startsWith("MAJOR") &&
                !cu.startsWith("PART") && !cu.startsWith("MDC") && !cu.startsWith("COMMON")) {
                if (!record.extractedCodes.contains(c)) {
                    record.extractedCodes.add(c);
                }
                if (record.courseCode == null) {
                    record.courseCode = c;
                }
            }
        }

        String[] rawTokens = clean.split("[_]+");
        for (String rawT : rawTokens) {
            String t = rawT.trim();
            String tu = t.toUpperCase();
            if (tu.matches("^[A-Z]{2,4}\\s*\\d[A-Z0-9_./–—\\-]*(?:\\s*\\([0-9A-Z]+\\))?$") || tu.matches("^\\d\\.\\d\\s+[A-Z]+$")) {
                if (!tu.startsWith("REG") && !tu.startsWith("SDE") && !tu.startsWith("EDE") &&
                    !tu.startsWith("CORE") && !tu.startsWith("MINOR") && !tu.startsWith("MAJOR") &&
                    !tu.startsWith("FROM") && !tu.startsWith("PART") && !tu.startsWith("MDC") &&
                    !tu.endsWith("TH") && !tu.endsWith("ST") && !tu.endsWith("ND") && !tu.endsWith("RD")) {
                    if (!record.extractedCodes.contains(t)) {
                        record.extractedCodes.add(t);
                    }
                    if (record.courseCode == null) {
                        record.courseCode = t;
                    }
                }
            }
        }

        // 4. Extract clean title from filename
        // Strip portal prefix e.g. REG_02.06.2026_10_00 AM_
        String fnTitle = clean.replaceFirst("(?i)^(?:REG|SDE|EDE)[_\\s]+[0-9._\\s]+(?:AM|PM|FN|AN)[_\\s]*", "");
        // Strip trailing code e.g. --(ECO2 C05) or ____COM2CJ101_
        fnTitle = fnTitle.replaceAll("--\\s*\\(.*?\\)", "");
        fnTitle = fnTitle.replaceAll("[_\\s]+[A-Za-z]{2,4}\\d[A-Za-z0-9_]*_?$", "");
        fnTitle = fnTitle.replaceAll("\\([A-Za-z]{2,4}\\s*\\d.*?\\)", "");
        fnTitle = fnTitle.replaceAll("\\b\\d{10,}\\b", ""); // timestamps
        fnTitle = fnTitle.replaceAll("[_\\s]+", " ").trim();

        if (fnTitle.length() >= 3) {
            String normFnTitle = normalizeTitle(fnTitle);
            if (!record.candidateTitles.contains(normFnTitle)) {
                record.candidateTitles.add(normFnTitle);
            }
            if (record.courseName == null) {
                record.courseName = fnTitle;
            }
        }

        if (record.stream == null) {
            record.stream = "Regular";
        }
    }

    // =========================================================================
    // PARSING SEATING JSON REQUIREMENTS
    // =========================================================================

    public List<SeatingRequirement> parseRequirementsFromJson(JsonNode root) {
        Map<String, SeatingRequirement> reqMap = new LinkedHashMap<>();

        if (root.isArray()) {
            for (JsonNode node : root) {
                String cname = node.path("courseName").asText("").trim();
                String pdfFn = node.path("pdfFileName").asText("").trim();
                String stream = node.path("stream").asText("").trim();
                if (stream.isEmpty() || "Regular".equalsIgnoreCase(stream)) {
                    String cu = cname.toUpperCase();
                    String pu = pdfFn.toUpperCase();
                    if (cu.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*") || pu.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*")) {
                        stream = "EDE";
                    } else if (stream.isEmpty()) {
                        stream = "Regular";
                    }
                }

                String date = node.path("date").asText("").trim();
                String time = node.path("time").asText("").trim();
                int count = node.path("count").asInt(0);
                int scribeCount = node.path("scribeCount").asInt(0);
                int roomSerial = node.path("roomSerial").asInt(0);

                final String finalStream = stream;
                String key = finalStream + "__" + cname + "__" + date + "__" + time;

                SeatingRequirement req = reqMap.computeIfAbsent(key, k -> {
                    SeatingRequirement r = new SeatingRequirement();
                    r.originalCourseName = cname;
                    r.pdfFileName = pdfFn;
                    r.stream = finalStream;
                    r.date = date;
                    r.time = time;
                    parseCourseNameFields(cname, pdfFn, r);
                    return r;
                });

                req.totalCandidates += count;
                req.totalScribes += scribeCount;
                if (roomSerial > 0 && !req.roomSerials.contains(roomSerial)) {
                    req.roomSerials.add(roomSerial);
                }
                req.matchingJsonNodes.add(node);
            }
        }
        return new ArrayList<>(reqMap.values());
    }

    private void parseCourseNameFields(String cname, String pdfFn, SeatingRequirement req) {
        String work = cname;
        if (work.isEmpty()) work = pdfFn;

        // 1. Syllabus Year: [2024 syllabus] or [2019 SYLLABUS]
        Pattern pSyl = Pattern.compile("\\[([^\\]]*?(20\\d\\d)[^\\]]*?)\\]", Pattern.CASE_INSENSITIVE);
        Matcher mSyl = pSyl.matcher(work);
        if (mSyl.find()) {
            req.syllabusYear = mSyl.group(2);
            work = work.replace(mSyl.group(0), " ").trim();
        }

        // 2. Strip degree category tags e.g. (Core 2), (Minor 3), (Minor 4), (Core(E) 1), (MDC 2)
        Pattern pCat = Pattern.compile("\\(\\s*(Core(?:\\([A-Za-z]+\\))?\\s*\\d*|Minor\\s*\\d*|Major\\s*\\d*|Common\\s*\\d*|Complementary\\s*\\d*|Open\\s*\\d*|Elective\\s*\\d*|MDC\\s*\\d*)\\s*\\)", Pattern.CASE_INSENSITIVE);
        work = pCat.matcher(work).replaceAll(" ").trim();

        // 3. Look for prefix course code: e.g. HIN5EJ301 (1) - ... or BOT5CJ303 - ...
        Pattern pPre = Pattern.compile("^([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9()/:\\s–—\\-]*?)\\s*[-–—:]+\\s*(.+)$");
        Matcher mPre = pPre.matcher(work);
        if (mPre.find()) {
            String candCode = mPre.group(1).trim();
            String candRem = mPre.group(2).trim();
            if (candCode.toUpperCase().matches("^[A-Z]{2,4}\\s*\\d.*")) {
                req.extractedCourseCode = candCode;
                work = candRem;
            }
        }

        // 4. Look for code after '--' e.g. --(COM2CJ101) or --(5.5 BCH) or --(COM2FM106 (1))
        if (req.extractedCourseCode == null) {
            Pattern pDashCode = Pattern.compile("--\\s*\\(?\\s*([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_./\\s–—\\-]*(?:\\([0-9A-Za-z]+\\)[A-Za-z0-9_./\\s–—\\-]*)*|\\d\\.\\d\\s*[A-Za-z0-9]+)\\s*\\)?");
            Matcher mDash = pDashCode.matcher(work);
            if (mDash.find()) {
                req.extractedCourseCode = mDash.group(1).trim();
                work = work.replace(mDash.group(0), " ").trim();
            }
        }

        // 5. Look for code in parentheses (including nested parens like (MAL2A08(1)) or (SKT2A 08 (1)) or (ECO1(2)C01))
        if (req.extractedCourseCode == null) {
            Pattern pCode = Pattern.compile("\\(\\s*([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_./\\s–—\\-]*(?:\\([0-9A-Za-z]+\\)[A-Za-z0-9_./\\s–—\\-]*)*|\\d\\.\\d\\s*[A-Za-z0-9]+)\\s*\\)");
            Matcher mCode = pCode.matcher(work);
            String found = null;
            while (mCode.find()) {
                String c = mCode.group(1).trim();
                String cu = c.toUpperCase();
                if (!cu.startsWith("CORE") && !cu.startsWith("MINOR") && !cu.startsWith("MAJOR") && 
                    !cu.startsWith("PART") && !cu.startsWith("SEMESTER") && !cu.startsWith("NOVEL") && !cu.startsWith("TRANSLATION")) {
                    found = c;
                }
            }
            if (found != null) {
                req.extractedCourseCode = found;
                work = work.replaceAll("\\([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_./\\s–—\\-]*(?:\\([0-9A-Za-z]+\\)[A-Za-z0-9_./\\s–—\\-]*)*\\)", " ").trim();
            }
        }

        if (req.extractedCourseCode != null) {
            req.normCourseCode = normalizeCode(req.extractedCourseCode);
        }

        req.extractedTitle = work.replaceAll("^[\\s-–—:_]+|[\\s-–—:_]+$", "").trim();
        req.normTitle = normalizeTitle(req.extractedTitle);
    }

    // =========================================================================
    // MATCHING DECISION ENGINE
    // =========================================================================

    public List<MatchDecision> matchAll(List<SeatingRequirement> requirements, List<QPRecord> qpRecords, String mode) {
        List<MatchDecision> decisions = new ArrayList<>();
        Map<SeatingRequirement, MatchDecision> decMap = new LinkedHashMap<>();

        for (SeatingRequirement req : requirements) {
            MatchDecision d = new MatchDecision();
            d.requirement = req;
            decMap.put(req, d);
        }

        // Build list of all candidate pairs and sort descending by score
        class ScoredPair {
            SeatingRequirement req;
            QPRecord qp;
            int score;
            List<String> reasons = new ArrayList<>();
            ScoredPair(SeatingRequirement r, QPRecord q, int s, List<String> res) {
                this.req = r; this.qp = q; this.score = s; this.reasons.addAll(res);
            }
        }

        List<ScoredPair> allPairs = new ArrayList<>();
        for (SeatingRequirement req : requirements) {
            for (QPRecord qp : qpRecords) {
                List<String> reasons = new ArrayList<>();
                int s = scorePair(req, qp, mode, reasons);
                if (s >= 45) {
                    allPairs.add(new ScoredPair(req, qp, s, reasons));
                }
            }
        }

        allPairs.sort((a, b) -> Integer.compare(b.score, a.score));

        // Maps to track assignments
        Set<SeatingRequirement> assignedReqs = new HashSet<>();
        Map<File, String> fileAssignedCodeMap = new HashMap<>();

        for (ScoredPair pair : allPairs) {
            if (assignedReqs.contains(pair.req)) continue;

            // Check if file is already assigned to a DIFFERENT course code
            if (fileAssignedCodeMap.containsKey(pair.qp.file)) {
                String existingCode = fileAssignedCodeMap.get(pair.qp.file);
                String reqCode = pair.req.normCourseCode != null ? pair.req.normCourseCode : "";
                // If course codes are both non-empty and different, this file belongs to a different subject
                if (!existingCode.isEmpty() && !reqCode.isEmpty() && !existingCode.equals(reqCode)) {
                    continue;
                }
            }

            // Assign!
            assignedReqs.add(pair.req);
            fileAssignedCodeMap.put(pair.qp.file, pair.req.normCourseCode != null ? pair.req.normCourseCode : "");

            MatchDecision d = decMap.get(pair.req);
            d.status = "EXACT";
            d.score = pair.score;
            d.matchedRecord = pair.qp;
            d.reasons.addAll(pair.reasons);
        }

        decisions.addAll(decMap.values());
        return decisions;
    }

    public int scorePair(SeatingRequirement req, QPRecord qp, String mode, List<String> reasonSink) {
        // 1. Strict Stream Guard
        boolean reqIsSde = req.stream != null && (req.stream.toUpperCase().contains("SDE") || req.stream.toUpperCase().contains("EDE"));
        boolean qpIsSde = (qp.qpCode != null && qp.qpCode.toUpperCase().endsWith("A")) ||
                          (qp.stream != null && (qp.stream.toUpperCase().contains("SDE") || qp.stream.toUpperCase().contains("EDE")));

        if (reqIsSde != qpIsSde) {
            if (reasonSink != null) reasonSink.add("Stream mismatch (" + req.stream + " vs " + qp.stream + ")");
            return -100;
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();
        reasons.add("Stream match (" + (reqIsSde ? "EDE" : "Regular") + ")");

        // 2. Course Code Matching
        boolean codeMatched = false;
        String reqCode = req.normCourseCode != null ? req.normCourseCode : "";

        if (!reqCode.isEmpty() && !qp.extractedCodes.isEmpty()) {
            for (String qc : qp.extractedCodes) {
                String nQc = normalizeCode(qc);
                if (reqCode.equals(nQc)) {
                    score += 70;
                    if (req.extractedCourseCode != null && req.extractedCourseCode.trim().equalsIgnoreCase(qc.trim())) {
                        score += 5;
                    }
                    codeMatched = true;
                    reasons.add("Exact code match (" + reqCode + ")");
                    break;
                } else if (reqCode.length() >= 5 && nQc.length() >= 5 && 
                          (reqCode.startsWith(nQc) || nQc.startsWith(reqCode))) {
                    score += 60;
                    codeMatched = true;
                    reasons.add("Sub-code match (" + reqCode + " ~ " + nQc + ")");
                    break;
                }
            }
        }

        // 3. Title Matching
        boolean titleMatched = false;
        Set<String> reqWords = extractWords(req.normTitle);

        if (!req.normTitle.isEmpty() && !qp.candidateTitles.isEmpty()) {
            for (String qt : qp.candidateTitles) {
                if (qt.isEmpty()) continue;
                if (req.normTitle.equals(qt)) {
                    score += 40;
                    titleMatched = true;
                    reasons.add("Exact title match");
                    break;
                } else if (req.normTitle.length() > 6 && qt.length() > 6 && 
                          (req.normTitle.contains(qt) || qt.contains(req.normTitle))) {
                    score += 35;
                    titleMatched = true;
                    reasons.add("Substring title match");
                    break;
                } else {
                    Set<String> qWords = extractWords(qt);
                    Set<String> common = new HashSet<>(reqWords);
                    common.retainAll(qWords);
                    if (!common.isEmpty()) {
                        double ratio = (double) common.size() / Math.min(reqWords.size(), qWords.size());
                        if (ratio >= 0.4) {
                            int pts = (int)(35 * ratio);
                            score += pts;
                            titleMatched = true;
                            reasons.add("Title overlap " + String.format("%.2f", ratio) + " (" + pts + " pts)");
                            break;
                        }
                    }
                }
            }
        }

        // Full Token Subset Bonus: If all words of requirement title appear in candidate title
        if (!codeMatched && !reqWords.isEmpty() && !qp.candidateTitles.isEmpty()) {
            for (String qt : qp.candidateTitles) {
                Set<String> qWords = extractWords(qt);
                if (qWords.containsAll(reqWords)) {
                    score += 25;
                    titleMatched = true;
                    reasons.add("Full token subset match (" + reqWords.size() + " words)");
                    break;
                }
            }
        }

        // 4. Syllabus Year Match
        if (req.syllabusYear != null && qp.syllabusYear != null && !req.syllabusYear.isEmpty() && !qp.syllabusYear.isEmpty()) {
            if (req.syllabusYear.equals(qp.syllabusYear)) {
                score += 15;
                reasons.add("Exact syllabus match (" + req.syllabusYear + ")");
            } else {
                try {
                    int rY = Integer.parseInt(req.syllabusYear.replaceAll("[^0-9]", ""));
                    int qY = Integer.parseInt(qp.syllabusYear.replaceAll("[^0-9]", ""));
                    if (Math.abs(rY - qY) <= 1) {
                        score += 5; // Adjacent year in same regulation
                    } else {
                        score -= 25;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (reasonSink != null) {
            reasonSink.clear();
            reasonSink.addAll(reasons);
        }

        // High confidence threshold
        if (codeMatched) {
            return score;
        }
        if (titleMatched && score >= 45) {
            return score;
        }

        return -50;
    }

    // =========================================================================
    // UTILITY HELPERS
    // =========================================================================

    public static String normalizeCode(String code) {
        if (code == null) return "";
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    public static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim().toUpperCase();
    }

    public static Set<String> extractWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) return words;
        Set<String> stop = Set.of("AND", "FOR", "THE", "IN", "OF", "ON", "TO", "WITH", "PART", "CORE", "MINOR", "MAJOR", "1", "2", "3", "4", "5");
        for (String w : normalizeTitle(text).split("\\s+")) {
            if (w.length() > 2 && !stop.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    public static List<String> extractAllCodes(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return codes;

        for (String part : text.split("[,/]")) {
            String clean = part.trim();
            if (!clean.isEmpty()) {
                codes.add(clean);
            }
        }
        Pattern p = Pattern.compile("[A-Za-z]{2,4}\\s*\\d[A-Za-z0-9().–—-]*(?:\\s+[A-Za-z0-9().–—-]+)*");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String c = m.group(0).trim();
            if (!codes.contains(c)) {
                codes.add(c);
            }
        }
        return codes;
    }
}
