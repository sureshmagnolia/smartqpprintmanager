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

 * Robust 3-Key Question Paper & Seating Requirement Matching Engine.

 * Implements strict 3-pillar validation:

 * 1. Spaceless Course Code

 * 2. Course Title (with vernacular script alignment & token overlap)

 * 3. Syllabus Year compatibility

 * 4. Stream alignment (Regular vs SDE/EDE)

 */

public class QPMatcherEngine {

    private static final Logger logger = LoggerFactory.getLogger(QPMatcherEngine.class);



    // Regex patterns

    private static final Pattern QP_CODE_REGEX = Pattern.compile("(?:^|[^A-Z0-9])([A-Z])\\s*(\\d{5,6})(?:\\s*([ABab]))?(?![a-zA-Z0-9])");

    private static final Pattern FN_QP_REGEX = Pattern.compile("(?:^|[\\s_\\-])([A-Za-z])?(\\d{5,6})([ABab])?(?:[\\s_\\-]|$)");

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

                        lines.add(clean);

                    }

                }

            }

        } catch (Exception e) {

            logger.warn("PDFBox text extraction failed for {}: {}", file.getName(), e.getMessage());

        }



        // 1. QP Code

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



        // 2. Syllabus Year

        for (String l : lines) {

            Matcher mRange = YEAR_RANGE_REGEX.matcher(l);

            if (mRange.find()) {

                record.syllabusYear = mRange.group(1) + "-" + mRange.group(2);

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
        if (fnUpper.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*") || fnUpper.matches(".*(?:^|[\\s_\\-.])(EDE|SDE|DISTANCE|PRIVATE)(?:$|[\\s_\\-.]).*")) {
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

        // 3. Robust Course Code & Title Extraction

        String[] DEPT_NAMES = {

            "ACCOUNTING AND FINANCE", "COMPUTER SCIENCE", "ELECTRONICS", "APPLIED PHYSICS", "COMMUNICATION",

            "PHILOSOPHY", "MATHEMATICS", "CHEMISTRY", "BOTANY", "COMMERCE", "ECONOMICS", 

            "HISTORY", "PHYSICS", "ZOOLOGY", "MALAYALAM", "ENGLISH", "HINDI", "SANSKRIT", 

            "TAMIL", "GEOGRAPHY"

        };

        Pattern pCodePrefix = Pattern.compile("^[A-Za-z]{2,4}\\s*\\d");

        Pattern pDelimHeader = Pattern.compile("^([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_()./\\s]*?)\\s*[-–—:]+\\s*([A-Za-z\\u0D00-\\u0D7F].+)$");



        for (int i = 0; i < Math.min(lines.size(), 25); i++) {

            String rawL = lines.get(i).trim();

            if (rawL.matches("(?i).*(DEGREE EXAMINATION|NOVEMBER|PAGES|REGISTER|TURN OVER|TIME\\s*:|MAXIMUM|NAME\\b|MARKS\\b).*")) {

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

                String[] words = cCand.split("\\s+");

                boolean validCode = words.length > 0;

                for (String w : words) {

                    if (w.equals("/") || w.equals("-") || w.equals("–") || w.equals("—")) continue;

                    boolean hasDig = false;

                    for (char ch : w.toCharArray()) { if (Character.isDigit(ch)) { hasDig = true; break; } }

                    boolean isShort = w.length() <= 4 && w.equals(w.toUpperCase());

                    boolean isParen = w.startsWith("(") && w.endsWith(")");

                    if (!hasDig && !isShort && !isParen) {

                        validCode = false;

                        break;

                    }

                }

                if (validCode && tCand.length() >= 2 && !tCand.matches("(?i)^(Section|Part|Time|Marks|Maximum|All Questions).*")) {

                    record.courseCode = cCand;

                    record.courseName = tCand;

                    record.fullCourseTitle = cleanL;

                    break;

                }

            }



            // Approach B: Multi-line (Code on line i, Title on line i+1)

            String cleanCodeOnly = cleanL.replaceAll("[-–—: ]+$", "").trim();

            String[] codeWords = cleanCodeOnly.split("\\s+");

            boolean codeOnlyValid = codeWords.length > 0;

            for (String w : codeWords) {

                if (w.equals("/")) continue;

                boolean hasDig = false;

                for (char ch : w.toCharArray()) { if (Character.isDigit(ch)) { hasDig = true; break; } }

                boolean isShort = w.length() <= 4 && w.equals(w.toUpperCase());

                boolean isParen = w.startsWith("(") && w.endsWith(")");

                if (!hasDig && !isShort && !isParen) {

                    codeOnlyValid = false;

                    break;

                }

            }

            if (codeOnlyValid && i + 1 < lines.size()) {

                String nextL = lines.get(i + 1).trim();

                if (nextL.length() >= 3 && !nextL.matches("(?i).*(ADMISSION|SEMESTER|EXAMINATION|MAXIMUM|TIME|SECTION|PART|TOTAL PAGES|NAME\\b|REGISTER).*")) {

                    record.courseCode = cleanCodeOnly;

                    record.courseName = nextL;

                    record.fullCourseTitle = record.courseCode + " - " + record.courseName;

                    break;

                }

            }



            // Approach C: Space-delimited code and title on the same line (no dash)

            String[] parts = cleanL.split("\\s+");

            List<String> cParts = new ArrayList<>();

            List<String> tParts = new ArrayList<>();

            boolean foundTitle = false;



            for (int pIdx = 0; pIdx < parts.length; pIdx++) {

                String p = parts[pIdx];

                if (foundTitle) {

                    tParts.add(p);

                    continue;

                }

                String cleanP = p.replaceAll("^[()/–—\\-:]+|[()/–—\\-:]+$", "").trim();

                boolean hasDig = false;

                for (char ch : cleanP.toCharArray()) { if (Character.isDigit(ch)) { hasDig = true; break; } }

                boolean isShort = cleanP.length() <= 3 && cleanP.equals(cleanP.toUpperCase());

                boolean isSlash = p.equals("/");

                boolean isAlt = cleanP.matches("^[A-Za-z]{2,4}\\d.*");



                if (pIdx == 0 || hasDig || isShort || isSlash || isAlt) {

                    cParts.add(p);

                } else {

                    foundTitle = true;

                    tParts.add(p);

                }

            }



            if (!cParts.isEmpty() && !tParts.isEmpty()) {

                String cStr = String.join(" ", cParts).replaceAll("[-–—: ]+$", "").trim();

                String tStr = String.join(" ", tParts).replaceAll("^[-–—: ]+", "").trim();

                if (cStr.length() <= 40 && tStr.length() >= 3 && !tStr.matches("(?i)^(Section|Part|Time|Marks|Maximum|All Questions|Admission).*")) {

                    record.courseCode = cStr;

                    record.courseName = tStr;

                    record.fullCourseTitle = cleanL;

                    break;

                }

            }

        }



        // 4. Filename metadata fallback

        parseFilenameFallback(file.getName(), record);



        // 5. OCR Fallback for vector or scanned PDFs if code or name is still missing

        if (record.courseCode == null || record.courseName == null || record.courseCode.isEmpty()) {

            tryOcrFallback(file, record);

        }



        // Compile extracted candidate codes and titles

        if (record.courseCode != null) {

            String rawCode = record.courseCode.trim();

            if (!rawCode.isEmpty()) {

                record.extractedCodes.add(rawCode);

            }

            record.extractedCodes.addAll(extractAllCodes(record.courseCode));

        }

        if (record.courseName != null) {

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

            int exitCode = proc.waitFor();

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

                    logger.info("OCR extraction succeeded for {}: {} — {}", file.getName(), record.courseCode, record.courseName);

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

        if (upper.matches(".*\\b(EDE|SDE|DISTANCE|PRIVATE)\\b.*") || upper.matches(".*(?:^|[\\s_\\-.])(EDE|SDE|DISTANCE|PRIVATE)(?:$|[\\s_\\-.]).*")) {

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



        // 3. Course code & name from filename if not yet extracted

        if (record.courseCode == null || record.courseName == null) {

            String[] parts = clean.split("_");

            for (String p : parts) {

                String pt = p.trim();

                if (pt.isEmpty() || pt.matches("^\\d{6,}$")) continue;

                if (pt.equalsIgnoreCase("REG") || pt.equalsIgnoreCase("REGULAR") || pt.equalsIgnoreCase("SDE") || pt.equalsIgnoreCase("EDE")) continue;



                Matcher mParen = Pattern.compile("\\(([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9_()./\\s–—-]+?)\\)").matcher(pt);

                String parenCode = null;

                while (mParen.find()) {

                    parenCode = mParen.group(1).trim();

                }

                if (parenCode != null) {

                    if (record.courseCode == null) record.courseCode = parenCode;

                    if (record.courseName == null) record.courseName = pt.replaceAll("\\(.*?\\)", "").trim();

                } else if (record.courseCode == null && Pattern.compile("^[A-Za-z]{2,4}\\s*\\d").matcher(pt).find()) {

                    record.courseCode = pt;

                } else if (record.courseName == null && pt.length() > 3 && !pt.matches("^\\d+$")) {

                    record.courseName = pt;

                }

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



        // Syllabus Year: [2024 syllabus] or [2019 SYLLABUS]

        Pattern pSyl = Pattern.compile("\\[([^\\]]*?(20\\d\\d)[^\\]]*?)\\]", Pattern.CASE_INSENSITIVE);

        Matcher mSyl = pSyl.matcher(work);

        if (mSyl.find()) {

            req.syllabusYear = mSyl.group(2);

            work = work.replace(mSyl.group(0), "").trim();

        }



        // Code in parentheses: (MTS5B08) or (MAT5EJ301(1))

        Pattern pCode = Pattern.compile("\\(([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9()./\\s–—-]+?)\\)");

        Matcher mCode = pCode.matcher(work);

        String foundCode = null;

        while (mCode.find()) {

            foundCode = mCode.group(1).trim();

        }



        if (foundCode != null) {

            req.extractedCourseCode = foundCode;

            req.normCourseCode = normalizeCode(foundCode);

            work = work.replaceAll("\\([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9()./\\s–—-]+?\\)", "").trim();

        } else {

            // Check prefix: BOT5CJ303 - Genetics...

            Pattern pPre = Pattern.compile("^([A-Za-z]{2,4}\\s*\\d[A-Za-z0-9()–—-]+?)\\s*[-–—:]+\\s*(.+)$");

            Matcher mPre = pPre.matcher(work);

            if (mPre.find()) {

                req.extractedCourseCode = mPre.group(1).trim();

                req.normCourseCode = normalizeCode(req.extractedCourseCode);

                work = mPre.group(2).trim();

            }

        }



        req.extractedTitle = work.replaceAll("^[\\s-–—:]+|[\\s-–—:]+$", "").trim();

        req.normTitle = normalizeTitle(req.extractedTitle);

    }



    // =========================================================================

    // MATCHING DECISION ENGINE

    // =========================================================================



    public List<MatchDecision> matchAll(List<SeatingRequirement> requirements, List<QPRecord> qpRecords, String mode) {

        List<MatchDecision> decisions = new ArrayList<>();

        Set<File> assignedFiles = new HashSet<>();



        for (SeatingRequirement req : requirements) {

            MatchDecision bestDecision = new MatchDecision();

            bestDecision.requirement = req;

            int bestScore = -999;



            for (QPRecord qp : qpRecords) {

                if (assignedFiles.contains(qp.file)) continue;

                int score = scorePair(req, qp, mode, bestDecision.reasons);

                if (score > bestScore) {

                    bestScore = score;

                    bestDecision.score = score;

                    bestDecision.matchedRecord = qp;

                }

            }



            if (bestScore >= 80 && bestDecision.matchedRecord != null) {

                bestDecision.status = "EXACT";

                assignedFiles.add(bestDecision.matchedRecord.file);

            } else {

                bestDecision.status = "UNMATCHED";

                bestDecision.matchedRecord = null;

            }

            decisions.add(bestDecision);

        }



        // Note: Strict stream separation enforced. If 2 streams exist (e.g. Regular and EDE),

        // they are separate exams and require separate PDF files. Unmatched stream remains UNMATCHED.



        return decisions;

    }



    public int scorePair(SeatingRequirement req, QPRecord qp, String mode, List<String> reasonSink) {

        int score = 0;

        List<String> reasons = new ArrayList<>();



        // 1. Spaceless Code Matching

        boolean codeMatched = false;

        boolean differentElective = false;

        String reqCode = req.normCourseCode != null ? req.normCourseCode : "";



        if (!reqCode.isEmpty() && !qp.extractedCodes.isEmpty()) {

            for (String qc : qp.extractedCodes) {

                String nQc = normalizeCode(qc);

                if (reqCode.equals(nQc)) {

                    score += 50;

                    codeMatched = true;

                    reasons.add("Exact spaceless code match (" + reqCode + ")");

                    break;

                } else if (reqCode.length() >= 6 && nQc.length() >= 6 && 

                          (reqCode.startsWith(nQc) || nQc.startsWith(reqCode))) {

                    score += 45;

                    codeMatched = true;

                    reasons.add("Sub-code match (" + reqCode + " ~ " + nQc + ")");

                    break;

                } else if (reqCode.length() >= 6 && nQc.length() >= 6 && 

                          reqCode.substring(0, 5).equals(nQc.substring(0, 5))) {

                    // Same department, different elective number (e.g. MAT5EJ303 vs MAT5EJ301)

                    differentElective = true;

                    reasons.add("Different elective in same department (" + reqCode + " vs " + nQc + ")");

                }

            }

        }



        // 2. Title Matching & Vernacular Native Script Alignment

        boolean titleMatched = false;

        boolean titleMismatch = false;



        boolean isVernacular = qp.candidateTitles.stream().anyMatch(t -> VERNACULAR_SCRIPT_REGEX.matcher(t).find()) ||

                               (req.normCourseCode != null && req.normCourseCode.matches("^(MAL|TML|HIN|SKT|ARB|URD).*"));



        if (!req.normTitle.isEmpty()) {

            for (String qt : qp.candidateTitles) {

                if (qt.isEmpty()) continue;

                if (req.normTitle.equals(qt)) {

                    score += 40;

                    titleMatched = true;

                    reasons.add("Exact title match");

                    break;

                } else if (req.normTitle.length() > 6 && qt.length() > 6 && 

                          (req.normTitle.contains(qt) || qt.contains(req.normTitle))) {

                    score += 30;

                    titleMatched = true;

                    reasons.add("Substring title match");

                    break;

                } else {

                    Set<String> rWords = extractWords(req.normTitle);

                    Set<String> qWords = extractWords(qt);

                    Set<String> common = new HashSet<>(rWords);

                    common.retainAll(qWords);

                    if (!common.isEmpty()) {

                        double ratio = (double) common.size() / Math.min(rWords.size(), qWords.size());

                        if (ratio >= 0.5) {

                            int pts = (int)(30 * ratio);

                            score += pts;

                            titleMatched = true;

                            reasons.add("Title token overlap (" + pts + " pts)");

                            break;

                        }

                    }

                }

            }

        }



        if (!titleMatched && codeMatched && isVernacular) {

            titleMatched = true;

            score += 35;

            reasons.add("Vernacular title in native script for language course");

        }



        // Title mismatch penalty (Strict subject conflict guard)

        if (!titleMatched && req.normTitle.length() > 6) {

            for (String qt : qp.candidateTitles) {

                if (qt.length() > 6) {

                    if (codeMatched && isVernacular) continue;

                    Set<String> rWords = extractWords(req.normTitle);

                    Set<String> qWords = extractWords(qt);

                    Set<String> common = new HashSet<>(rWords);

                    common.retainAll(qWords);

                    if (!rWords.isEmpty() && !qWords.isEmpty() && common.isEmpty()) {

                        titleMismatch = true;

                        score -= 40;

                        reasons.add("Subject conflict: '" + req.normTitle + "' != '" + qt + "'");

                        break;

                    }

                }

            }

        }



        if (differentElective && !titleMatched) {

            score -= 50;

        }



        // 3. Stream match: STRICT STREAM GUARD

        // "if there 2 streams the files will be 2 diffrent even if the title and codes are all same"

        boolean reqIsSde = req.stream != null && (req.stream.toUpperCase().contains("SDE") || req.stream.toUpperCase().contains("EDE"));

        boolean qpIsSde = (qp.qpCode != null && qp.qpCode.toUpperCase().endsWith("A")) ||

                          (qp.stream != null && (qp.stream.toUpperCase().contains("SDE") || qp.stream.toUpperCase().contains("EDE")));



        if (reqIsSde == qpIsSde) {

            score += 15;

            reasons.add("Stream match (" + (reqIsSde ? "EDE/SDE" : "Regular") + ")");

        } else {

            score -= 100;

            reasons.add("Stream mismatch: Course is " + req.stream + " but PDF is " + (qpIsSde ? "EDE" : "Regular"));

            return -100; // Immediate hard rejection for mismatched streams

        }



        // 4. Syllabus Year match

        boolean yrCompat = false;

        if (req.syllabusYear != null && qp.syllabusYear != null) {

            if (req.syllabusYear.equals(qp.syllabusYear)) {

                score += 15;

                yrCompat = true;

                reasons.add("Exact syllabus match (" + req.syllabusYear + ")");

            } else if (req.syllabusYear.contains(qp.syllabusYear) || qp.syllabusYear.contains(req.syllabusYear)) {

                score += 10;

                yrCompat = true;

                reasons.add("Syllabus range overlap");

            } else {

                score -= 35;

                reasons.add("Syllabus mismatch (" + req.syllabusYear + " vs " + qp.syllabusYear + ")");

            }

        } else {

            yrCompat = true;

        }



        if (reasonSink != null) {

            reasonSink.clear();

            reasonSink.addAll(reasons);

        }



        if (titleMismatch || differentElective) {

            return -100;

        }



        return score;

    }



    // =========================================================================

    // UTILITY HELPERS

    // =========================================================================



    public static String normalizeCode(String code) {

        if (code == null) return "";

        return code.replaceAll("[^A-Za-z0-9()]", "").toUpperCase();

    }



    public static String normalizeTitle(String title) {

        if (title == null) return "";

        return title.replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim().toUpperCase();

    }



    private static Set<String> extractWords(String text) {

        Set<String> words = new HashSet<>();

        for (String w : text.split("\\s+")) {

            if (w.length() > 3) {

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



