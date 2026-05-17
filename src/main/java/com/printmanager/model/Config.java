package com.printmanager.model;

import java.util.ArrayList;
import java.util.List;

public class Config {
    private List<PrintRule> rules = new ArrayList<>();
    private String splitKeyword = "";
    private List<SmartSplitRule> smartSplitRules = new ArrayList<>();
    private List<FileItem> fileQueue = new ArrayList<>();
    private List<RoomGroup> roomGroups = new ArrayList<>();
    private String collegeId = "";
    private String baseDownloadPath = "";

    public Config() {
        // Initialize with default MCQ rule if none exist
        if (smartSplitRules.isEmpty()) {
            smartSplitRules.add(new SmartSplitRule());
        }
    }

    public String getCollegeId() { return collegeId; }
    public void setCollegeId(String collegeId) { this.collegeId = collegeId; }

    public List<PrintRule> getRules() { return rules; }
    public void setRules(List<PrintRule> rules) { this.rules = rules; }

    public String getSplitKeyword() { return splitKeyword; }
    public void setSplitKeyword(String splitKeyword) { this.splitKeyword = splitKeyword; }

    public List<SmartSplitRule> getSmartSplitRules() { return smartSplitRules; }
    public void setSmartSplitRules(List<SmartSplitRule> smartSplitRules) { this.smartSplitRules = smartSplitRules; }

    public List<FileItem> getFileQueue() { return fileQueue; }
    public void setFileQueue(List<FileItem> fileQueue) { this.fileQueue = fileQueue; }

    public List<RoomGroup> getRoomGroups() { return roomGroups; }
    public void setRoomGroups(List<RoomGroup> roomGroups) { this.roomGroups = roomGroups; }

    public String getBaseDownloadPath() { return baseDownloadPath; }
    public void setBaseDownloadPath(String baseDownloadPath) { this.baseDownloadPath = baseDownloadPath; }
}
