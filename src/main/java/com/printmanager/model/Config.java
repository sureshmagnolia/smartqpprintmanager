package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    private List<PrintRule> rules = new ArrayList<>();
    private String splitKeyword = "";
    private List<SmartSplitRule> smartSplitRules = new ArrayList<>();
    private String collegeId = "";
    private String portalUsername = "";
    private String portalPassword = "";
    private String portalQpPassword = "";
    private String portalQpPrefix = "";
    private String selectedExamId = "";
    private String currentExamName = "Exam";
    private String lastSessionName = "";
    private String collegeName = "Your Institution Name";
    private String currentExamDate = "";
    private String currentExamSession = "";
    private String baseDownloadPath = "";
    private String lastDirectory = "";
    private boolean printCoverPage = true;
    private boolean printSigningSheet = true;
    private boolean aiRoutingEnabled = false;

    public Config() {
        // Initialize with default MCQ rule if none exist
        if (smartSplitRules.isEmpty()) {
            smartSplitRules.add(new SmartSplitRule());
        }
    }

    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String collegeName) { this.collegeName = collegeName; }

    public String getCurrentExamDate() { return currentExamDate; }
    public void setCurrentExamDate(String currentExamDate) { this.currentExamDate = currentExamDate; }

    public String getCurrentExamSession() { return currentExamSession; }
    public void setCurrentExamSession(String currentExamSession) { this.currentExamSession = currentExamSession; }

    public boolean isPrintCoverPage() { return printCoverPage; }
    public void setPrintCoverPage(boolean printCoverPage) { this.printCoverPage = printCoverPage; }

    public boolean isPrintSigningSheet() { return printSigningSheet; }
    public void setPrintSigningSheet(boolean printSigningSheet) { this.printSigningSheet = printSigningSheet; }

    public boolean isAiRoutingEnabled() { return aiRoutingEnabled; }
    public void setAiRoutingEnabled(boolean aiRoutingEnabled) { this.aiRoutingEnabled = aiRoutingEnabled; }

    public String getCollegeId() { return collegeId; }
    public void setCollegeId(String collegeId) { this.collegeId = collegeId; }

    public String getPortalUsername() { return portalUsername; }
    public void setPortalUsername(String portalUsername) { this.portalUsername = portalUsername; }

    public String getPortalPassword() { return portalPassword; }
    public void setPortalPassword(String portalPassword) { this.portalPassword = portalPassword; }

    public String getPortalQpPassword() { return portalQpPassword; }
    public void setPortalQpPassword(String portalQpPassword) { this.portalQpPassword = portalQpPassword; }

    public String getPortalQpPrefix() { return portalQpPrefix; }
    public void setPortalQpPrefix(String portalQpPrefix) { this.portalQpPrefix = portalQpPrefix; }

    public String getSelectedExamId() { return selectedExamId; }
    public void setSelectedExamId(String selectedExamId) { this.selectedExamId = selectedExamId; }

    public List<PrintRule> getRules() { return rules; }
    public void setRules(List<PrintRule> rules) { this.rules = rules; }

    public String getSplitKeyword() { return splitKeyword; }
    public void setSplitKeyword(String splitKeyword) { this.splitKeyword = splitKeyword; }

    public List<SmartSplitRule> getSmartSplitRules() { return smartSplitRules; }
    public void setSmartSplitRules(List<SmartSplitRule> smartSplitRules) { this.smartSplitRules = smartSplitRules; }

    public String getCurrentExamName() { return currentExamName; }
    public void setCurrentExamName(String currentExamName) { this.currentExamName = currentExamName; }

    public String getLastSessionName() { return lastSessionName; }
    public void setLastSessionName(String lastSessionName) { this.lastSessionName = lastSessionName; }

    public String getBaseDownloadPath() { return baseDownloadPath; }
    public void setBaseDownloadPath(String baseDownloadPath) { this.baseDownloadPath = baseDownloadPath; }

    public String getLastDirectory() { return lastDirectory; }
    public void setLastDirectory(String lastDirectory) { this.lastDirectory = lastDirectory; }
}
