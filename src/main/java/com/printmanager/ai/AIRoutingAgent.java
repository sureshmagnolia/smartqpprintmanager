package com.printmanager.ai;

import com.printmanager.model.FileItem;
import com.printmanager.model.RoomItem;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIRoutingAgent {
    private final PDFDecrypter decrypter = new PDFDecrypter();

    private void logDebug(String msg) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("ai-debug.log"), (msg + "\n").getBytes(), 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {}
    }

    public MatchResult findBestFileForRoom(RoomItem room, List<FileItem> availableFiles) {
        logDebug("--- START SINGLE-MATCH FOR ROOM " + room.getRoomSerial() + " | " + room.getCourseName() + " ---");
        MatchResult bestResult = new MatchResult(false, room, 0.0);
        
        String roomCourseCode = extractCourseCodeFromText(room.getCourseName());
        String learnedQP = KnowledgeBase.getLearnedQP(room.getCourseName(), roomCourseCode);

        for (FileItem fileItem : availableFiles) {
            MatchResult tempResult = new MatchResult(false, room, 0.0);
            double confidence = evaluateMatch(fileItem, room, learnedQP, roomCourseCode, tempResult);
            
            logDebug("  -> File: " + fileItem.getFileName() + " | Score: " + confidence + " (Log: " + String.join(", ", tempResult.getWorkingLogs()) + ")");
            
            if (confidence > bestResult.getConfidenceScore()) {
                bestResult = new MatchResult(true, room, confidence);
                bestResult.setMatchedFile(fileItem);
                bestResult.getWorkingLogs().addAll(tempResult.getWorkingLogs());
                bestResult.addLog("NEW BEST MATCH: " + fileItem.getFileName() + " (Score: " + confidence + ")");
            }
        }
        
        if (bestResult.getConfidenceScore() >= 0.85) {
            bestResult.getMatchedFile().setAiLogs(String.join("\n", bestResult.getWorkingLogs()));
            logDebug("MATCH SUCCESS: " + bestResult.getMatchedFile().getFileName() + " (Score: " + bestResult.getConfidenceScore() + ")");
            return bestResult;
        }

        logDebug("NO MATCHES >= 85% for: " + room.getCourseName());
        return new MatchResult(false, room, 0.0);
    }

    public List<MatchResult> findAllMatchesForRoom(RoomItem room, List<FileItem> availableFiles) {
        logDebug("--- START MULTI-MATCH FOR ROOM " + room.getRoomSerial() + " | " + room.getCourseName() + " ---");
        List<MatchResult> results = new java.util.ArrayList<>();

        String roomCourseCode = extractCourseCodeFromText(room.getCourseName());
        String learnedQP = KnowledgeBase.getLearnedQP(room.getCourseName(), roomCourseCode);

        for (FileItem fileItem : availableFiles) {
            MatchResult tempResult = new MatchResult(false, room, 0.0);
            double confidence = evaluateMatch(fileItem, room, learnedQP, roomCourseCode, tempResult);
            
            // STRICT DECISIVE THRESHOLD (V6.3)
            logDebug("  -> File: " + fileItem.getFileName() + " | Score: " + confidence + " (Log: " + String.join(", ", tempResult.getWorkingLogs()) + ")");
            if (confidence >= 0.85) { 
                MatchResult finalResult = new MatchResult(true, room, confidence);
                finalResult.setMatchedFile(fileItem);
                finalResult.getWorkingLogs().addAll(tempResult.getWorkingLogs());
                finalResult.addLog("DECISION: Verified Match score: " + (int)(confidence * 100) + "%");
                
                fileItem.setAiLogs(String.join("\n", finalResult.getWorkingLogs()));
                results.add(finalResult);
                logDebug("MATCH SUCCESS: " + fileItem.getFileName() + " (Score: " + confidence + ")");
            }
        }

        if (results.isEmpty()) {
            logDebug("NO MATCHES for: " + room.getCourseName());
        }
        return results;
    }

    private double evaluateMatch(FileItem fileItem, RoomItem room, String learnedQP, String roomCC, MatchResult result) {
        File file = new File(fileItem.getFilePath());
        if (!file.exists()) return 0.0;

        PDFDecrypter.PDFMetadata metadata = decrypter.extractMetadata(file);
        double score = 0.0;

        String jsonQP = room.getQpCode() != null ? room.getQpCode().trim().toUpperCase() : "";
        boolean qpFromJSON = !jsonQP.isEmpty();

        // --- CRITICAL KNOWLEDGE BASE FALLBACK ---
        // If the JSON lacks a QP code, treat the learned QP as the actual QP for all validation
        if (jsonQP.isEmpty() && learnedQP != null && !learnedQP.isEmpty()) {
            jsonQP = learnedQP.toUpperCase();
        }

        String internalQP = metadata.qpCode != null ? metadata.qpCode.trim().toUpperCase() : "";
        String filenameQP = extractQPFromFileName(fileItem.getFileName()).toUpperCase();

        // --- PRE-PROCESSING: Base QP Match ---
        String baseJsonQP = jsonQP.replaceAll("^D", "").replaceAll("[A-Z]$", "");
        String baseInternalQP = internalQP.replaceAll("^D", "").replaceAll("[A-Z]$", "");
        String baseFilenameQP = filenameQP.replaceAll("^D", "").replaceAll("[A-Z]$", "");

        String normJsonQP = jsonQP.replaceAll("^D", "").replaceAll("\\s+", "");
        String normInternalQP = internalQP.replaceAll("^D", "").replaceAll("\\s+", "");
        String normFilenameQP = filenameQP.replaceAll("^D", "").replaceAll("\\s+", "");

        boolean isStrongBaseQP = false;
        if (!baseJsonQP.isEmpty() && (baseJsonQP.equals(baseInternalQP) || baseJsonQP.equals(baseFilenameQP))) {
            isStrongBaseQP = true;
        }

        // --- LAYER 0: STRICT STREAM VALIDATION (MIMIC LEGACY) ---
        String jsonStream = room.getStream();
        String roomName = room.getCourseName() != null ? room.getCourseName().toUpperCase() : "";
        
        // Use a unified detection method (parity with isSDEStream in App.java)
        boolean jsonIsExt = (jsonStream != null && (jsonStream.toUpperCase().contains("SDE") || 
                            jsonStream.toUpperCase().contains("EDE") || 
                            jsonStream.toUpperCase().contains("DISTANCE") || 
                            jsonStream.toUpperCase().contains("EXTERNAL"))) || 
                            roomName.contains(" SDE") || roomName.contains("(SDE)") || 
                            roomName.contains("DISTANCE") || roomName.contains("EXTERNAL");
// PDF stream detection:
// A PDF is SDE if its filename contains SDE/DISTANCE markers OR if it has 'A' suffix / 'D' prefix 
// UNLESS it is explicitly marked as Regular (REG_ prefix).
boolean pdfIsExt = metadata.stream.equalsIgnoreCase("SDE");
String fn = fileItem.getFileName().toUpperCase();
if (fn.contains("REG_") || fn.contains("_REG_") || fn.contains(" REG ")) {
    pdfIsExt = false;
} else if (fn.contains("SDE") || fn.contains("EDE") || fn.contains("DISTANCE") ||
        internalQP.endsWith("A") || filenameQP.endsWith("A") ||
        internalQP.startsWith("D") || filenameQP.startsWith("D")) {
    pdfIsExt = true;
}

// Exact match bypass ONLY if the QP code came directly from JSON
boolean isExactQP = qpFromJSON && (normJsonQP.equals(normInternalQP) || normJsonQP.equals(normFilenameQP));

// REJECTION RULE: If streams mismatch and we don't have an EXACT match from JSON, REJECT.
if (jsonIsExt != pdfIsExt && !isExactQP) {
    result.addLog("REJECT: Stream Mismatch (Room:" + (jsonIsExt?"EXT":"REG") + " vs PDF:" + (pdfIsExt?"EXT":"REG") + ")");
    return 0.0;
}

// Stream Tie-Breaker: Give a significant bonus if the filename literally contains the stream word
if (jsonIsExt && (fn.contains("SDE") || fn.contains("EDE") || fn.contains("DISTANCE") || fn.contains("EXTER"))) score += 0.05;
if (!jsonIsExt && (fn.contains("REG") || fn.contains("NORMAL"))) score += 0.05;
        // --- LAYER 1: SPLIT PROTECTION (MIMIC LEGACY) ---
        // If the room is Regular, it should NOT take papers that are purely MCQ or purely SDE-specialized
        // UNLESS it's the Main part of a split.
        if (!jsonIsExt) {
             if (fn.contains("MCQ") && !fn.contains("MAIN") && !fn.contains("REMAIN")) {
                 result.addLog("REJECT: MCQ-only file in Regular hall");
                 return 0.0;
             }
        }

        // --- LAYER 2: QP CODE MATCH ---
        if (!baseJsonQP.isEmpty()) {
            if (isExactQP) {
                score += 0.95;
                if (jsonIsExt == pdfIsExt) score += 0.05;
                result.addLog("MATCH: Exact QP Code Match (" + jsonQP + ")");
            } else if (isStrongBaseQP) {
                score += 0.90;
                if (jsonIsExt == pdfIsExt) score += 0.1;
                result.addLog("MATCH: Base QP Code Equality (" + baseJsonQP + ")");
            } else if (baseJsonQP.startsWith(baseInternalQP) && !baseInternalQP.isEmpty()) {
                score += 0.85; 
                result.addLog("MATCH: QP Prefix (" + baseInternalQP + " in " + baseJsonQP + ")");
            } else if (baseInternalQP.startsWith(baseJsonQP)) {
                score += 0.85;
                result.addLog("MATCH: QP Substring (" + baseJsonQP + " in " + baseInternalQP + ")");
            }
        } else if (!baseInternalQP.isEmpty() || !baseFilenameQP.isEmpty()) {
            // Case where JSON is empty (No_QP_Codes), but PDF has code. Self-heal via filenames.
            String expectedFile = room.getPdfFileName() != null ? room.getPdfFileName().toUpperCase() : "";
            if (!expectedFile.isEmpty() && ((!baseInternalQP.isEmpty() && expectedFile.contains(baseInternalQP)) || 
                                            (!baseFilenameQP.isEmpty() && expectedFile.contains(baseFilenameQP)))) {
                score += 0.9;
                result.addLog("MATCH: Missing QP Self-Heal from Filename (Base QP matches expected file)");
            }
        }

        // --- LAYER 3: KNOWLEDGE BASE ---
        if (learnedQP != null && !learnedQP.isEmpty()) {
            String baseLearnedQP = learnedQP.replaceAll("^D", "").replaceAll("[A-Z]$", "");
            if (baseLearnedQP.equals(baseInternalQP) || baseLearnedQP.equals(baseFilenameQP)) {
                score += 0.5;
                result.addLog("MATCH: Knowledge Base Hit (" + learnedQP + ")");
            }
        }

        // --- LAYER 4: COURSE CODE MATCH ---
        if (roomCC != null && !roomCC.isEmpty() && !metadata.courseCode.isEmpty()) {
            String normRoomCC = roomCC.replaceAll("[^A-Z0-9]", "");
            String normPDFCC = metadata.courseCode.replaceAll("[^A-Z0-9]", "");
            if (normRoomCC.equals(normPDFCC)) {
                score += 0.4; // Reduced contribution to allow stream preference to dominate
                result.addLog("MATCH: Course Code Identity (" + normPDFCC + ")");
            } else if (normRoomCC.contains(normPDFCC) || normPDFCC.contains(normRoomCC)) {
                score += 0.35;
                result.addLog("MATCH: Course Code Inclusion (" + normPDFCC + " ~ " + normRoomCC + ")");
            }
        }
        
        // --- LAYER 5: FUZZY SUBJECT MATCH (Optional/Weak) ---
        if (score < 0.8) {
            String normSubject = room.getCourseName().toUpperCase().replaceAll("[^A-Z0-9]", "");
            String normRaw = metadata.rawText.toUpperCase().replaceAll("[^A-Z0-9]", "");
            if (normRaw.contains(normSubject) && normSubject.length() > 10) {
                score += 0.4;
                result.addLog("WEAK: Fuzzy Subject Content Match");
            }
        }

        // --- FINAL RECOVERY: If score is high, ensure the result knows what QP this file "identifies as"
        if (score >= 0.8) {
             String qpToReport = !internalQP.isEmpty() ? internalQP : filenameQP;
             if (!qpToReport.isEmpty()) {
                 if (jsonIsExt && !qpToReport.endsWith("A") && qpToReport.matches(".*\\d$")) {
                     qpToReport += "A";
                 }
                 result.addLog("SELF-HEAL: Identifies as QP: " + qpToReport);
             }
        }

        return Math.min(score, 1.0);
    }

    private String extractCourseCodeFromText(String text) {
        if (text == null) return "";
        Pattern p = Pattern.compile("\\(([A-Z0-9().-]+)\\)");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).toUpperCase();
        return "";
    }

    private String extractQPFromFileName(String fileName) {
        if (fileName == null) return "";
        Pattern[] patterns = {
            Pattern.compile("_(\\d{5,8})_"),
            Pattern.compile("_([A-Z0-9]{5,10})[._]")
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(fileName);
            if (m.find()) return m.group(1);
        }
        return "";
    }
}
