package com.printmanager.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class RoomItem {
    private final String roomSerial;
    private final String qpCode;
    private final String pdfFileName;
    private final int count;
    private final ObservableList<FileItem> matchedFiles = FXCollections.observableArrayList();
    private final StringProperty status = new SimpleStringProperty("Pending");

    public RoomItem(String roomSerial, String qpCode, String pdfFileName, int count) {
        this.roomSerial = roomSerial;
        this.qpCode = qpCode;
        this.pdfFileName = pdfFileName;
        this.count = count;
    }

    public String getRoomSerial() { return roomSerial; }
    public String getQpCode() { return qpCode; }
    public String getPdfFileName() { return pdfFileName; }
    public int getCount() { return count; }

    public ObservableList<FileItem> getMatchedFiles() { return matchedFiles; }

    public String getStatus() { return status.get(); }
    public StringProperty statusProperty() { return status; }
    public void setStatus(String status) { this.status.set(status); }
}
