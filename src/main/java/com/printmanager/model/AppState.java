package com.printmanager.model;

import java.util.ArrayList;
import java.util.List;

public class AppState {
    private List<FileItem> fileQueue = new ArrayList<>();
    private List<RoomGroup> roomGroups = new ArrayList<>();

    public List<FileItem> getFileQueue() { return fileQueue; }
    public void setFileQueue(List<FileItem> fileQueue) { this.fileQueue = fileQueue; }

    public List<RoomGroup> getRoomGroups() { return roomGroups; }
    public void setRoomGroups(List<RoomGroup> roomGroups) { this.roomGroups = roomGroups; }
}