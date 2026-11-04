package com.printmanager.reports.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * In-memory unified snapshot of ExamFlow data.
 */
public class ExamFlowDataSet {
    private String collegeName = "University of Calicut";
    private List<String> allSessions = new ArrayList<>();
    private Map<String, List<JsonNode>> roomAllotments = new HashMap<>();
    private Map<String, Map<String, String>> qpCodes = new HashMap<>();
    private Map<String, JsonNode> roomConfigs = new HashMap<>();
    private List<JsonNode> scribes = new ArrayList<>();
    private Map<String, Map<String, String>> scribeAllotments = new HashMap<>();
    private List<JsonNode> staffList = new ArrayList<>();
    private Map<String, Map<String, String>> invigilatorMappings = new HashMap<>();
    private List<JsonNode> absenteeList = new ArrayList<>();
    private Map<String, List<String>> absenteeMap = new HashMap<>();
    private List<JsonNode> allStudents = new ArrayList<>();
    private List<JsonNode> examRulesConfig = new ArrayList<>();

    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String collegeName) { 
        if (collegeName != null && !collegeName.trim().isEmpty()) {
            this.collegeName = collegeName.trim();
        }
    }

    public List<String> getAllSessions() { return allSessions; }
    public void setAllSessions(List<String> allSessions) { this.allSessions = allSessions; }

    public Map<String, List<JsonNode>> getRoomAllotments() { return roomAllotments; }
    public void setRoomAllotments(Map<String, List<JsonNode>> roomAllotments) { this.roomAllotments = roomAllotments; }

    public Map<String, Map<String, String>> getQpCodes() { return qpCodes; }
    public void setQpCodes(Map<String, Map<String, String>> qpCodes) { this.qpCodes = qpCodes; }

    public Map<String, JsonNode> getRoomConfigs() { return roomConfigs; }
    public void setRoomConfigs(Map<String, JsonNode> roomConfigs) { this.roomConfigs = roomConfigs; }

    public List<JsonNode> getScribes() { return scribes; }
    public void setScribes(List<JsonNode> scribes) { this.scribes = scribes; }

    public Map<String, Map<String, String>> getScribeAllotments() { return scribeAllotments; }
    public void setScribeAllotments(Map<String, Map<String, String>> scribeAllotments) { this.scribeAllotments = scribeAllotments; }

    public List<JsonNode> getStaffList() { return staffList; }
    public void setStaffList(List<JsonNode> staffList) { this.staffList = staffList; }

    public Map<String, Map<String, String>> getInvigilatorMappings() { return invigilatorMappings; }
    public void setInvigilatorMappings(Map<String, Map<String, String>> invigilatorMappings) { this.invigilatorMappings = invigilatorMappings; }

    public Map<String, List<String>> getAbsenteeList() { return absenteeMap; }
    public void setAbsenteeList(Map<String, List<String>> absenteeMap) { this.absenteeMap = absenteeMap; }

    public List<JsonNode> getAllStudents() { return allStudents; }
    public void setAllStudents(List<JsonNode> allStudents) { this.allStudents = allStudents; }

    public List<JsonNode> getExamRulesConfig() { return examRulesConfig; }
    public void setExamRulesConfig(List<JsonNode> examRulesConfig) { this.examRulesConfig = examRulesConfig; }

    public String getExamName(String sessionKey, String stream) {
        String targetStream = (stream != null && !stream.trim().isEmpty()) ? stream.trim() : "Regular";
        
        // 1. Check if students in this session have examName tagged
        List<JsonNode> rooms = roomAllotments.getOrDefault(sessionKey, Collections.emptyList());
        for (JsonNode room : rooms) {
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    for (String k : new String[]{"examName", "Exam Name", "exam_name", "Exam"}) {
                        if (s.has(k) && !s.get(k).asText("").trim().isEmpty()) {
                            return s.get(k).asText("").trim();
                        }
                    }
                }
            }
        }

        // 2. Fallback to examRulesConfig matching stream
        if (examRulesConfig != null && !examRulesConfig.isEmpty()) {
            for (JsonNode rule : examRulesConfig) {
                String rStream = rule.path("stream").asText("");
                String rName = rule.path("examName").asText("");
                if (!rName.isEmpty() && (rStream.equalsIgnoreCase(targetStream) || rStream.equalsIgnoreCase("All Streams") || rStream.isEmpty())) {
                    return rName;
                }
            }
            // Return first exam name found
            for (JsonNode rule : examRulesConfig) {
                String rName = rule.path("examName").asText("");
                if (!rName.isEmpty()) return rName;
            }
        }

        return "";
    }

    public boolean isEmpty() {
        return allSessions.isEmpty() && roomAllotments.isEmpty() && allStudents.isEmpty();
    }
}

