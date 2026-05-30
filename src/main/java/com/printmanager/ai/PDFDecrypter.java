package com.printmanager.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PDFDecrypter {
    private static final Logger logger = LoggerFactory.getLogger(PDFDecrypter.class);

    public static class PDFMetadata {
        public String qpCode = "";
        public String courseCode = "";
        public String year = "";
        public String stream = "Regular"; // Default
        public String rawText = "";

        @Override
        public String toString() {
            return String.format("QP:%s, Course:%s, Year:%s, Stream:%s", qpCode, courseCode, year, stream);
        }
    }

    private void logDebug(String msg) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("ai-debug.log"), (msg + "\n").getBytes(), 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {}
    }

    public PDFMetadata extractMetadata(File file) {
        PDFMetadata metadata = new PDFMetadata();
        try {
            // High-Level Extraction
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                metadata.rawText = stripper.getText(document);
            }

            // Low-Level Fallback / Supplemental Scan (Raw Stream Grep)
            String rawHead = readRawHeader(file, 256000); 

            String searchPool = metadata.rawText + " [RAW]: " + rawHead;

            // --- 1. Extract QP Code ---
            // Pattern handles: 140753, D140753, 140753A
            Pattern qpPattern = Pattern.compile("(?i)\\b(D?\\s*\\d{5,8}[A-Z]?)\\b");
            Matcher qm = qpPattern.matcher(searchPool);
            while (qm.find()) {
                String val = qm.group(1).replaceAll("\\s+", "").toUpperCase();
                if (val.length() >= 5) {
                    metadata.qpCode = val;
                    break;
                }
            }

            // --- 2. Extract Course Code ---
            // Pattern handles: ENG4A06, BCM4B06, ZOO4CJ203, 4.4 BCH, ENG4FV109(1B)
            Pattern ccPattern = Pattern.compile("\\b([A-Z]{2,6}\\s*\\d[A-Z0-9().-]{1,12}|\\d\\.\\d\\s*[A-Z]{2,4})\\b", Pattern.CASE_INSENSITIVE);
            Matcher ccMatcher = ccPattern.matcher(searchPool);
            while (ccMatcher.find()) {
                String val = ccMatcher.group(1).toUpperCase().replaceAll("\\s+", "");
                if (isValidCourseCode(val)) {
                    metadata.courseCode = val;
                    break;
                }
            }
            
            // Fallback for "Shredded" codes
            if (metadata.courseCode.isEmpty()) {
                 metadata.courseCode = reassembleShreddedCode(searchPool);
            }

            // --- 3. Extract Year ---
            Pattern yearPattern = Pattern.compile("(201[789]|202[0-5])");
            extractUsingPattern(yearPattern, searchPool, val -> metadata.year = val);

            // --- 4. Extract Stream ---
            String ucText = metadata.rawText.toUpperCase();
            String fnText = file.getName().toUpperCase();
            
            boolean isExternal = ucText.contains(" SDE") || ucText.contains("(SDE)") || 
                                 ucText.contains("DISTANCE") || ucText.contains("EXTERNAL") || 
                                 ucText.contains(" EDE") || fnText.contains("SDE") || 
                                 fnText.contains("EDE") || fnText.contains("DISTANCE");
            
            boolean isRegular = fnText.contains("REG_");

            if (isExternal && !isRegular) {
                metadata.stream = "SDE";
            } else if (isRegular) {
                metadata.stream = "Regular";
            } else {
                // If neither explicit flag, fallback to text cues
                if (isExternal) metadata.stream = "SDE";
                else metadata.stream = "Regular";
            }

            logDebug("Extracted from " + file.getName() + ": " + metadata.toString());

        } catch (Exception e) {
            logDebug("ERROR extracting from " + file.getName() + ": " + e.getMessage());
            logger.error("Failed to extract metadata from PDF: {}", file.getName(), e);
        }
        return metadata;
    }

    private void extractUsingPattern(Pattern p, String text, java.util.function.Consumer<String> setter) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String val = m.group(1);
            if (val != null && !val.isEmpty()) {
                setter.accept(val);
                return; 
            }
        }
    }

    private String reassembleShreddedCode(String text) {
        // Look for sequences of (A) (B) (C) or similar fragmented patterns
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("\\(([A-Z0-9().-]+)\\)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            sb.append(m.group(1));
            if (sb.length() > 500) break; // Increased buffer for full header reassembly
        }
        
        String candidate = sb.toString().toUpperCase();
        // Look for Alpha + Numeric + AlphaNumeric (standard university format)
        Pattern codePattern = Pattern.compile("([A-Z]{2,4}\\d[A-Z0-9]{1,7}|\\d\\.\\d[A-Z]{2,4})");
        Matcher cm = codePattern.matcher(candidate);
        if (cm.find()) {
            String found = cm.group(1);
            if (isValidCourseCode(found)) return found;
        }

        // Secondary search for common bases if the pattern is slightly off
        String[] commonBases = {"BCM", "BBA", "ENG", "MTS", "PHY", "CHE", "BOT", "ZOO", "HIS", "ECO", "COM", "MAL", "HIN", "ARA"};
        for (String base : commonBases) {
            int idx = candidate.indexOf(base);
            if (idx >= 0) {
                // Try to extract 7 characters from the base (e.g. BCM4B06)
                String sub = candidate.substring(idx, Math.min(idx + 10, candidate.length()));
                Matcher sm = codePattern.matcher(sub);
                if (sm.find()) return sm.group(1);
            }
        }
        return "";
    }

    private boolean isValidCourseCode(String code) {
        if (code == null || code.length() < 3) return false;
        String uc = code.toUpperCase();
        String[] blackList = {"APRIL", "JUNE", "JULY", "SEPT", "OCTO", "NOVE", "DECE", "YEAR", "PAGE", "TOTAL"};
        for (String b : blackList) {
            if (uc.contains(b)) return false;
        }
        return true;
    }

    private String readRawHeader(File file, int bytes) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] data = new byte[bytes];
            int read = fis.read(data);
            if (read > 0) {
                // Use ISO_8859_1 to preserve bytes as chars without corruption
                return new String(data, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .replaceAll("[^\\x20-\\x7E]", " ");
            }
        } catch (IOException e) {}
        return "";
    }
}
