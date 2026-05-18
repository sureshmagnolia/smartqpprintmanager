package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class RoomItem {
    private String roomSerial;
    private String qpCode;
    private String pdfFileName;
    private String courseName;
    private final IntegerProperty count = new SimpleIntegerProperty();
    private FileItem matchedFile;
    private final StringProperty status = new SimpleStringProperty("Pending");

    public RoomItem() {}

    @JsonCreator
    public RoomItem(
            @JsonProperty("roomSerial") String roomSerial,
            @JsonProperty("qpCode") String qpCode,
            @JsonProperty("pdfFileName") String pdfFileName,
            @JsonProperty("count") int count) {
        this.roomSerial = roomSerial;
        this.qpCode = qpCode;
        this.pdfFileName = pdfFileName;
        this.count.set(count);
    }

    public String getRoomSerial() { return roomSerial; }
    public void setRoomSerial(String roomSerial) { this.roomSerial = roomSerial; }

    public String getQpCode() { return qpCode; }
    public void setQpCode(String qpCode) { this.qpCode = qpCode; }

    public String getPdfFileName() { return pdfFileName; }
    public void setPdfFileName(String pdfFileName) { this.pdfFileName = pdfFileName; }
    
    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }
    
    @JsonProperty("count")
    public int getCount() { return count.get(); }
    public void setCount(int count) { this.count.set(count); }
    @JsonIgnore
    public IntegerProperty countProperty() { return count; }

    public FileItem getMatchedFile() { return matchedFile; }
    public void setMatchedFile(FileItem matchedFile) { this.matchedFile = matchedFile; }

    @JsonProperty("status")
    public String getStatus() { return status.get(); }
    public void setStatus(String status) { this.status.set(status); }
    @JsonIgnore
    public StringProperty statusProperty() { return status; }

    @JsonIgnore
    public String getDisplayName() {
        if (matchedFile != null) {
            String fname = matchedFile.getFileName().toLowerCase();
            if (fname.contains("mcq")) return qpCode + " (MCQ)";
            if (fname.contains("main")) return qpCode + " (Main)";
            if (fname.startsWith("split_")) return qpCode + " (Split)";
            if (fname.startsWith("remain_")) return qpCode + " (Remain)";
        }
        return qpCode;
    }
}
