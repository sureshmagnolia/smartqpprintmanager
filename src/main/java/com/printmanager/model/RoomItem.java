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
    private final ObjectProperty<FileItem> matchedFile = new SimpleObjectProperty<>();
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

    @JsonProperty("matchedFile")
    public FileItem getMatchedFile() { return matchedFile.get(); }
    public void setMatchedFile(FileItem matchedFile) { this.matchedFile.set(matchedFile); }
    @JsonIgnore
    public ObjectProperty<FileItem> matchedFileProperty() { return matchedFile; }

    @JsonProperty("status")
    public String getStatus() { return status.get(); }
    public void setStatus(String status) { this.status.set(status); }
    @JsonIgnore
    public StringProperty statusProperty() { return status; }

    @JsonIgnore
    public String getDisplayName() {
        FileItem matched = matchedFile.get();
        if (matched != null) {
            String fname = matched.getFileName().toLowerCase();
            if (fname.contains("mcq")) return qpCode + " (MCQ)";
            if (fname.contains("main")) return qpCode + " (Main)";
            if (fname.startsWith("split_")) return qpCode + " (Split)";
            if (fname.startsWith("remain_")) return qpCode + " (Remain)";
        }
        return qpCode;
    }
}
