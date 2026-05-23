package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.ArrayList;
import java.util.List;

public class RoomGroup {
    @JsonProperty("roomSerial")
    private String roomSerial;
    
    private final ObservableList<RoomItem> items = FXCollections.observableArrayList();
    private final StringProperty selectedPrinter = new SimpleStringProperty("None");
    private final StringProperty status = new SimpleStringProperty("Ready");
    private final IntegerProperty totalStudents = new SimpleIntegerProperty(0);

    public RoomGroup() {}

    @JsonCreator
    public RoomGroup(@JsonProperty("roomSerial") String roomSerial) {
        this.roomSerial = roomSerial;
    }

    public String getRoomSerial() { return roomSerial; }
    public void setRoomSerial(String roomSerial) { this.roomSerial = roomSerial; }

    @JsonProperty("totalStudents")
    public int getTotalStudents() { return totalStudents.get(); }
    public void setTotalStudents(int totalStudents) { this.totalStudents.set(totalStudents); }
    @JsonIgnore
    public IntegerProperty totalStudentsProperty() { return totalStudents; }

    @JsonProperty("items")
    public List<RoomItem> getItemsList() { return new ArrayList<>(items); }
    public void setItemsList(List<RoomItem> items) { this.items.setAll(items); }

    @JsonIgnore
    public ObservableList<RoomItem> getItems() { return items; }

    @JsonProperty("selectedPrinter")
    public String getSelectedPrinter() { return selectedPrinter.get(); }
    public void setSelectedPrinter(String printer) { this.selectedPrinter.set(printer); }
    @JsonIgnore
    public StringProperty selectedPrinterProperty() { return selectedPrinter; }

    @JsonProperty("status")
    public String getStatus() { return status.get(); }
    public void setStatus(String status) { this.status.set(status); }
    @JsonIgnore
    public StringProperty statusProperty() { return status; }
}
