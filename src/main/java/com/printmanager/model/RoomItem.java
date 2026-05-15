package com.printmanager.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class RoomItem {
    private final String roomSerial;
    private final String qpCode;
    private final String pdfFileName;
    private final IntegerProperty count;
    private FileItem matchedFile;
    private final StringProperty status = new SimpleStringProperty("Pending");

    public RoomItem(String roomSerial, String qpCode, String pdfFileName, int count) {
        this.roomSerial = roomSerial;
        this.qpCode = qpCode;
        this.pdfFileName = pdfFileName;
        this.count = new SimpleIntegerProperty(count);
    }

    public String getRoomSerial() { return roomSerial; }
    public String getQpCode() { return qpCode; }
    public String getPdfFileName() { return pdfFileName; }
    
    public int getCount() { return count.get(); }
    public IntegerProperty countProperty() { return count; }
    public void setCount(int count) { this.count.set(count); }

    public FileItem getMatchedFile() { return matchedFile; }
    public void setMatchedFile(FileItem matchedFile) { this.matchedFile = matchedFile; }

    public String getStatus() { return status.get(); }
    public StringProperty statusProperty() { return status; }
    public void setStatus(String status) { this.status.set(status); }

    public String getDisplayName() {
        if (matchedFile != null) {
            String fname = matchedFile.getFileName().toLowerCase();
            if (fname.contains("mcq")) return qpCode + " (MCQ)";
            if (fname.contains("main")) return qpCode + " (Main)";
        }
        return qpCode;
    }
}
