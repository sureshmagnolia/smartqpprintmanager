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
    private Map<String, List<String>> absenteeList = new HashMap<>();
    private List<JsonNode> allStudents = new ArrayList<>();

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

    public Map<String, List<String>> getAbsenteeList() { return absenteeList; }
    public void setAbsenteeList(Map<String, List<String>> absenteeList) { this.absenteeList = absenteeList; }

    public List<JsonNode> getAllStudents() { return allStudents; }
    public void setAllStudents(List<JsonNode> allStudents) { this.allStudents = allStudents; }

    public boolean isEmpty() {
        return allSessions.isEmpty() && roomAllotments.isEmpty() && allStudents.isEmpty();
    }
}
