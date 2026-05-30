package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.*;

public class RoomItem {
    private final StringProperty roomSerial = new SimpleStringProperty();
    private final StringProperty qpCode = new SimpleStringProperty();
    private final StringProperty pdfFileName = new SimpleStringProperty();
    private final StringProperty courseName = new SimpleStringProperty();
    private final StringProperty stream = new SimpleStringProperty();
    private final IntegerProperty sourceNodeId = new SimpleIntegerProperty(-1);
    
    private final IntegerProperty count = new SimpleIntegerProperty();
    private final ObjectProperty<FileItem> matchedFile = new SimpleObjectProperty<>();
    private final StringProperty status = new SimpleStringProperty("Pending");

    public RoomItem() {}

    @JsonCreator
    public RoomItem(
            @JsonProperty("roomSerial") String roomSerial,
            @JsonProperty("qpCode") String qpCode,
            @JsonProperty("pdfFileName") String pdfFileName,
            @JsonProperty("count") int count,
            @JsonProperty("sourceNodeId") int sourceNodeId) {
        this.roomSerial.set(roomSerial);
        this.qpCode.set(qpCode);
        this.pdfFileName.set(pdfFileName);
        this.count.set(count);
        this.sourceNodeId.set(sourceNodeId);
    }

    @JsonProperty("roomSerial")
    public String getRoomSerial() { return roomSerial.get(); }
    public void setFileRoomSerial(String roomSerial) { this.roomSerial.set(roomSerial); }
    @JsonIgnore
    public StringProperty roomSerialProperty() { return roomSerial; }

    @JsonProperty("qpCode")
    public String getQpCode() { return qpCode.get(); }
    public void setQpCode(String qpCode) { this.qpCode.set(qpCode); }
    @JsonIgnore
    public StringProperty qpCodeProperty() { return qpCode; }

    @JsonProperty("pdfFileName")
    public String getPdfFileName() { return pdfFileName.get(); }
    public void setPdfFileName(String pdfFileName) { this.pdfFileName.set(pdfFileName); }
    @JsonIgnore
    public StringProperty pdfFileNameProperty() { return pdfFileName; }
    
    @JsonProperty("courseName")
    public String getCourseName() { return courseName.get(); }
    public void setCourseName(String courseName) { this.courseName.set(courseName); }
    @JsonIgnore
    public StringProperty courseNameProperty() { return courseName; }

    @JsonProperty("stream")
    public String getStream() { return stream.get(); }
    public void setStream(String stream) { this.stream.set(stream); }
    @JsonIgnore
    public StringProperty streamProperty() { return stream; }

    @JsonProperty("sourceNodeId")
    public int getSourceNodeId() { return sourceNodeId.get(); }
    public void setSourceNodeId(int id) { this.sourceNodeId.set(id); }
    @JsonIgnore
    public IntegerProperty sourceNodeIdProperty() { return sourceNodeId; }
    
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
        String qp = qpCode.get();
        if (qp == null || qp.isEmpty()) qp = "NO QP";
        
        if (matched != null) {
            String fname = matched.getFileName().toLowerCase();
            if (fname.contains("mcq")) return qp + " (MCQ)";
            if (fname.contains("main")) return qp + " (Main)";
            if (fname.startsWith("split_")) return qp + " (Split)";
            if (fname.startsWith("remain_")) return qp + " (Remain)";
        }
        return qp;
    }
}
