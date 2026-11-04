package com.printmanager.reports.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportPackItem {
    private String reportId;
    private boolean enabled = true;
    private int copies = 1;
    private boolean duplex;

    public ReportPackItem() {}

    public ReportPackItem(String reportId, boolean enabled, int copies, boolean duplex) {
        this.reportId = reportId;
        this.enabled = enabled;
        this.copies = copies;
        this.duplex = duplex;
    }

    public static ReportPackItem fromReportType(ReportType type) {
        return new ReportPackItem(type.getId(), true, 1, type.isDefaultDuplex());
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = Math.max(1, copies); }

    public boolean isDuplex() { return duplex; }
    public void setDuplex(boolean duplex) { this.duplex = duplex; }

    public ReportType getReportType() {
        return ReportType.fromId(reportId);
    }
}
