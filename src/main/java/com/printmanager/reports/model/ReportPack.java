package com.printmanager.reports.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportPack {
    private String id;
    private String name;
    private String description;
    private String targetPrinter = "Default";
    private List<ReportPackItem> items = new ArrayList<>();

    public ReportPack() {}

    public ReportPack(String id, String name, String description, String targetPrinter, List<ReportPackItem> items) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.targetPrinter = targetPrinter != null ? targetPrinter : "Default";
        this.items = items != null ? items : new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTargetPrinter() { return targetPrinter; }
    public void setTargetPrinter(String targetPrinter) { this.targetPrinter = targetPrinter; }

    public List<ReportPackItem> getItems() { return items; }
    public void setItems(List<ReportPackItem> items) { this.items = items; }

    public ReportPack copy() {
        List<ReportPackItem> clonedItems = new ArrayList<>();
        for (ReportPackItem item : items) {
            clonedItems.add(new ReportPackItem(item.getReportId(), item.isEnabled(), item.getCopies(), item.isDuplex()));
        }
        return new ReportPack(this.id, this.name, this.description, this.targetPrinter, clonedItems);
    }
}

