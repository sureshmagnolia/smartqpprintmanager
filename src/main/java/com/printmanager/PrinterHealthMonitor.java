package com.printmanager;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PrinterHealthMonitor {
    private static final Logger logger = LoggerFactory.getLogger(PrinterHealthMonitor.class);
    private final String printerName;
    private final StringProperty healthStatus = new SimpleStringProperty("Checking...");
    private final ScheduledExecutorService scheduler;

    public PrinterHealthMonitor(String printerName) {
        this.printerName = printerName;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PrinterHealthMonitor-" + printerName);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::pollStatus, 0, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public StringProperty healthStatusProperty() {
        return healthStatus;
    }

    private void pollStatus() {
        try {
            // Robust PowerShell command: Fetch all and filter in memory to avoid WMI escaping nightmares with special characters like ()
            String command = String.format(
                "Get-CimInstance -ClassName Win32_Printer | Where-Object { $_.Name -eq '%s' } | Select-Object DetectedErrorState, PrinterStatus, WorkOffline | ConvertTo-Json",
                printerName.replace("'", "''")
            );

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            Process p = pb.start();
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(p.getInputStream());

            String status = "Unknown";
            if (!root.isMissingNode() && !root.isNull()) {
                int printerStatus = root.path("PrinterStatus").asInt(0);
                int errorState = root.path("DetectedErrorState").asInt(0);
                boolean isOffline = root.path("WorkOffline").asBoolean(false);

                status = translateStatus(printerStatus, errorState, isOffline);
            } else {
                status = "Not Found";
            }

            final String finalStatus = status;
            Platform.runLater(() -> healthStatus.set(finalStatus));

        } catch (Exception e) {
            logger.error("Error polling status for printer: " + printerName, e);
            Platform.runLater(() -> healthStatus.set("Query Error"));
        }
    }

    private String translateStatus(int printerStatus, int errorState, boolean isOffline) {
        if (isOffline) return "Offline";

        // 1. Check for specific errors first (DetectedErrorState)
        switch (errorState) {
            case 1: return "Other Error";
            case 2: return "Unknown Error";
            case 3: return "No Error";
            case 4: return "Low Paper";
            case 5: return "No Paper";
            case 6: return "Low Toner";
            case 7: return "No Toner";
            case 8: return "Door Open";
            case 9: return "Jam";
            case 10: return "Offline";
            case 11: return "Service Req.";
            case 12: return "Output Bin Full";
        }

        // 2. Fallback to general PrinterStatus if no specific error
        switch (printerStatus) {
            case 1: return "Other";
            case 2: return "Unknown";
            case 3: return "Ready";
            case 4: return "Printing";
            case 5: return "Warmup";
            case 6: return "Stopped";
            case 7: return "Offline";
        }

        return "Ready";
    }
}
