package com.printmanager.ai;

import com.printmanager.model.RoomItem;
import java.util.ArrayList;
import java.util.List;

public class MatchResult {
    private final boolean isMatch;
    private final RoomItem matchedRoom;
    private com.printmanager.model.FileItem matchedFile;
    private final double confidenceScore;
    private final List<String> workingLogs;

    public MatchResult(boolean isMatch, RoomItem matchedRoom, double confidenceScore) {
        this.isMatch = isMatch;
        this.matchedRoom = matchedRoom;
        this.confidenceScore = confidenceScore;
        this.workingLogs = new ArrayList<>();
    }

    public boolean isMatch() { return isMatch; }
    public RoomItem getMatchedRoom() { return matchedRoom; }
    public com.printmanager.model.FileItem getMatchedFile() { return matchedFile; }
    public void setMatchedFile(com.printmanager.model.FileItem file) { this.matchedFile = file; }
    public double getConfidenceScore() { return confidenceScore; }
    public List<String> getWorkingLogs() { return workingLogs; }

    public void addLog(String log) {
        this.workingLogs.add("[AI Thought] " + log);
    }
}
