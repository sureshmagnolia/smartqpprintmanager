package com.printmanager;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ActivityLogger {
    public static class LogEntry {
        private final String timestamp;
        private final String message;
        private final String level;

        public LogEntry(String message, String level) {
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            this.message = message;
            this.level = level;
        }

        public String getTimestamp() { return timestamp; }
        public String getMessage() { return message; }
        public String getLevel() { return level; }
    }

    private final ObservableList<LogEntry> logs = FXCollections.observableArrayList();
    private static final ActivityLogger instance = new ActivityLogger();

    private ActivityLogger() {}

    public static ActivityLogger getInstance() { return instance; }

    public ObservableList<LogEntry> getLogs() { return logs; }

    public void log(String message, String level) {
        Platform.runLater(() -> {
            logs.add(0, new LogEntry(message, level));
            if (logs.size() > 500) logs.remove(500, logs.size());
        });
    }

    public void info(String message) { log(message, "INFO"); }
    public void warn(String message) { log(message, "WARN"); }
    public void error(String message) { log(message, "ERROR"); }
    public void success(String message) { log(message, "SUCCESS"); }
}
