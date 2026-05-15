package com.printmanager.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class RoomGroup {
    private final String roomSerial;
    private final ObservableList<RoomItem> items = FXCollections.observableArrayList();
    private final StringProperty selectedPrinter = new SimpleStringProperty("None");
    private final StringProperty status = new SimpleStringProperty("Ready");

    public RoomGroup(String roomSerial) {
        this.roomSerial = roomSerial;
    }

    public String getRoomSerial() { return roomSerial; }
    public ObservableList<RoomItem> getItems() { return items; }

    public String getSelectedPrinter() { return selectedPrinter.get(); }
    public StringProperty selectedPrinterProperty() { return selectedPrinter; }
    public void setSelectedPrinter(String printer) { this.selectedPrinter.set(printer); }

    public String getStatus() { return status.get(); }
    public StringProperty statusProperty() { return status; }
    public void setStatus(String status) { this.status.set(status); }
}
