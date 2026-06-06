package com.printmanager;

import com.printmanager.model.FileItem;
import com.printmanager.model.RoomGroup;
import com.printmanager.model.RoomItem;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ValidationService {
    
    private final ObservableList<RoomGroup> roomGroupsList;
    private final ObservableList<FileItem> fileQueue;
    private final ActivityLogger activityLogger;
    private final HBox healthCheckRibbon;
    private final App app;

    public ValidationService(App app, ObservableList<RoomGroup> roomGroupsList, ObservableList<FileItem> fileQueue, ActivityLogger activityLogger, HBox healthCheckRibbon) {
        this.app = app;
        this.roomGroupsList = roomGroupsList;
        this.fileQueue = fileQueue;
        this.activityLogger = activityLogger;
        this.healthCheckRibbon = healthCheckRibbon;
    }

    public void validateAppState() {
        validateAppState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public void validateAppState(List<String> extraErrors, List<String> extraWarnings, List<String> extraInfo) {
        List<String> missingFromQueue = new ArrayList<>(extraErrors);   // In JSON but NOT in Queue
        List<String> missingFromJSON = new ArrayList<>(extraInfo);      // In Queue but NOT in JSON
        List<String> unroutedWarnings = new ArrayList<>(extraWarnings); // In both, but no printer

        java.util.Map<String, FileItem> queueMap = new java.util.HashMap<>();
        java.util.Set<FileItem> routedFiles = new java.util.HashSet<>();
        java.util.Set<String> routedQPs = new java.util.HashSet<>();

        for (RoomGroup group : roomGroupsList) {
            for (RoomItem ri : group.getItems()) {
                if (ri.getMatchedFile() != null) {
                    routedFiles.add(ri.getMatchedFile());
                    String qp = app.extractQPFromFileName(ri.getMatchedFile().getFileName()).toUpperCase();
                    if (!qp.isEmpty()) routedQPs.add(qp);
                }
            }
        }

        for (FileItem item : fileQueue) {
            String qp = app.extractQPFromFileName(item.getFileName());
            if (!qp.isEmpty()) {
                queueMap.put(qp.toUpperCase(), item);
            }
        }

        java.util.Set<String> jsonQPs = new java.util.HashSet<>();
        for (RoomGroup group : roomGroupsList) {
            for (RoomItem item : group.getItems()) {
                String qp = item.getQpCode();
                if (qp != null && !qp.isEmpty()) {
                    jsonQPs.add(qp.toUpperCase());
                }
            }
        }

        for (FileItem item : fileQueue) {
            String fileName = item.getFileName();
            String qp = app.extractQPFromFileName(fileName).toUpperCase();
            
            boolean isMaster = false;
            if (!qp.isEmpty()) {
                int myLevel = app.getSpecificityLevel(fileName);
                for (FileItem other : fileQueue) {
                    if (other == item) continue;
                    if (qp.equalsIgnoreCase(app.extractQPFromFileName(other.getFileName()))) {
                        if (app.getSpecificityLevel(other.getFileName()) > myLevel) {
                            isMaster = true;
                            break;
                        }
                    }
                }
            }

            if (isMaster) {
                item.setStatus("Split Master (Idle)");
                continue; 
            }

            boolean isRouted = routedFiles.contains(item);
            if (qp.isEmpty() || !jsonQPs.contains(qp)) {
                missingFromJSON.add("❌ QP [" + (qp.isEmpty() ? "UNKNOWN" : qp) + "] Routing Data Missing: File " + item.getFileName() + " has no entry in JSON.");
                item.setStatus("No JSON Entry");
            } else {
                if (item.getTargetPrinter() == null || "None".equals(item.getTargetPrinter()) || item.getTargetPrinter().isEmpty()) {
                    if (!isRouted) {
                        unroutedWarnings.add("⚠️ QP [" + qp + "] No Printer Assigned to " + item.getFileName());
                    }
                }
            }
        }

        for (String qp : jsonQPs) {
            if (!queueMap.containsKey(qp)) {
                missingFromQueue.add("❌ QP [" + qp + "] Required by JSON but PDF is NOT LOADED");
            }
        }

        File sessionDir = app.getSessionDir();
        if (sessionDir != null && sessionDir.exists()) {
            File[] files = sessionDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
            if (files != null) {
                for (File f : files) {
                    String qp = app.extractQPFromFileName(f.getName()).toUpperCase();
                    if (!qp.isEmpty() && !queueMap.containsKey(qp)) {
                        missingFromJSON.add("ℹ️ QP [" + qp + "] File found on disk but NOT loaded in App: " + f.getName());
                    }
                }
            }
        }

        Runnable updateUI = () -> {
            healthCheckRibbon.getChildren().clear();
            int totalErrors = missingFromQueue.size() + missingFromJSON.stream().filter(s -> s.startsWith("❌")).collect(Collectors.toList()).size();
            int totalWarnings = unroutedWarnings.size();

            if (totalErrors == 0 && totalWarnings == 0) {
                healthCheckRibbon.setVisible(false);
                healthCheckRibbon.setManaged(false);
                activityLogger.success("Health Check: All files verified and synchronized with JSON.");
            } else {
                healthCheckRibbon.setVisible(true);
                healthCheckRibbon.setManaged(true);
                healthCheckRibbon.setSpacing(10);
                healthCheckRibbon.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                healthCheckRibbon.setStyle("-fx-background-color: #333; -fx-padding: 5 15;");

                Label alertLabel = new Label("STATIONERY HEALTH:");
                alertLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 11px;");

                Button detailBtn = new Button();
                String btnText = "";
                if (totalErrors > 0) btnText += "❌ " + totalErrors + " CRITICAL ERRORS ";
                if (totalWarnings > 0) btnText += "⚠️ " + totalWarnings + " ROUTING ISSUES";
                detailBtn.setText(btnText.trim());
                detailBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 10; -fx-cursor: hand;");
                
                detailBtn.setOnAction(e -> showDetailedHealthReport(missingFromQueue, missingFromJSON, unroutedWarnings));

                Button refreshBtn = new Button("Re-Scan");
                refreshBtn.setStyle("-fx-font-size: 10px; -fx-padding: 1 8;");
                refreshBtn.setOnAction(e -> validateAppState());

                healthCheckRibbon.getChildren().addAll(alertLabel, detailBtn, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, refreshBtn);
                
                missingFromQueue.forEach(msg -> activityLogger.error("Health Check: " + msg));
                missingFromJSON.forEach(msg -> {
                    if (msg.startsWith("❌")) activityLogger.error("Health Check: " + msg);
                    else activityLogger.info("Health Check: " + msg);
                });
                unroutedWarnings.forEach(msg -> activityLogger.warn("Health Check: " + msg));
            }
        };

        if (Platform.isFxApplicationThread()) {
            updateUI.run();
        } else {
            Platform.runLater(updateUI);
        }
    }

    private void showDetailedHealthReport(List<String> missing, List<String> newOnDisk, List<String> unrouted) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Stationery & Data Health Report");
        alert.setHeaderText("Discrepancies detected in your session data");
        
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) {
            sb.append("❌ MISSING PAPERS (In Queue but not on Disk):\n");
            missing.forEach(m -> sb.append("  ").append(m).append("\n"));
            sb.append("\n");
        }
        if (!newOnDisk.isEmpty()) {
            sb.append("❌ DATA MISSING (PDF on Disk but not in Queue):\n");
            newOnDisk.forEach(m -> sb.append("  ").append(m).append("\n"));
            sb.append("\n");
        }
        if (!unrouted.isEmpty()) {
            sb.append("⚠️ ROUTING ISSUES (No Printer Assigned):\n");
            unrouted.forEach(m -> sb.append("  ").append(m).append("\n"));
        }

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefHeight(450);
        textArea.setPrefWidth(600);
        textArea.setStyle("-fx-font-family: 'Consolas', 'Monospace'; -fx-font-size: 12px;");

        alert.getDialogPane().setExpandableContent(textArea);
        alert.getDialogPane().setExpanded(true);
        alert.show();
    }
}
