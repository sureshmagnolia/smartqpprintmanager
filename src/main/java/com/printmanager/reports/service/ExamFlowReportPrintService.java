package com.printmanager.reports.service;

import com.printmanager.ActivityLogger;
import com.printmanager.reports.model.ExamFlowDataSet;
import com.printmanager.reports.model.ReportPack;
import com.printmanager.reports.model.ReportPackItem;
import com.printmanager.reports.model.ReportType;
import javafx.application.Platform;
import javafx.print.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class ExamFlowReportPrintService {
    private static final Logger logger = LoggerFactory.getLogger(ExamFlowReportPrintService.class);
    private final ActivityLogger activityLogger;

    public ExamFlowReportPrintService(ActivityLogger activityLogger) {
        this.activityLogger = activityLogger != null ? activityLogger : ActivityLogger.getInstance();
    }

    /**
     * Prints an individual report using JavaFX WebEngine and PrinterJob.
     */
    public void printReport(ReportType type, ExamFlowDataSet dataSet, String sessionKey,
                            String printerName, Boolean duplexOverride, int copies,
                            Consumer<String> statusCallback) {
        if (type == null) {
            if (statusCallback != null) statusCallback.accept("Error: Null report type");
            return;
        }

        boolean duplex = (duplexOverride != null) ? duplexOverride : type.isDefaultDuplex();
        String html = ExamFlowReportRenderer.renderReport(type, dataSet, sessionKey);

        Platform.runLater(() -> {
            try {
                if (statusCallback != null) statusCallback.accept("Preparing " + type.getDisplayName() + "...");

                WebView webView = new WebView();
                WebEngine engine = webView.getEngine();

                engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                    if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                        try {
                            Printer targetPrinter = resolvePrinter(printerName);
                            PrinterJob job = PrinterJob.createPrinterJob(targetPrinter);
                            if (job == null) {
                                if (statusCallback != null) statusCallback.accept("Failed: No printer available");
                                return;
                            }

                            JobSettings settings = job.getJobSettings();
                            settings.setJobName("ExamFlow - " + type.getDisplayName());
                            settings.setCopies(Math.max(1, copies));

                            // Statutory Duplex / Simplex enforcement
                            if (duplex) {
                                settings.setPrintSides(PrintSides.DUPLEX);
                            } else {
                                settings.setPrintSides(PrintSides.ONE_SIDED);
                            }

                            // Standard A4 Layout
                            PageLayout pageLayout = targetPrinter.createPageLayout(
                                    Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.DEFAULT
                            );
                            settings.setPageLayout(pageLayout);

                            if (statusCallback != null) statusCallback.accept("Sending to " + targetPrinter.getName() + " (" + (duplex ? "Duplex" : "Simplex") + ")...");
                            activityLogger.info("Printing ExamFlow Report: " + type.getDisplayName() + " -> " + targetPrinter.getName() + " (Duplex: " + duplex + ", Copies: " + copies + ")");

                            engine.print(job);
                            job.endJob();

                            if (statusCallback != null) statusCallback.accept("Printed ✔ (" + type.getDisplayName() + ")");
                        } catch (Exception ex) {
                            logger.error("Error executing print job for {}: {}", type.getDisplayName(), ex.getMessage(), ex);
                            if (statusCallback != null) statusCallback.accept("Print Error: " + ex.getMessage());
                        }
                    } else if (newState == javafx.concurrent.Worker.State.FAILED) {
                        if (statusCallback != null) statusCallback.accept("Render Failed for " + type.getDisplayName());
                    }
                });

                engine.loadContent(html);
            } catch (Exception e) {
                logger.error("Failed to initialize WebEngine print: {}", e.getMessage(), e);
                if (statusCallback != null) statusCallback.accept("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Prints an entire custom or preset Report Pack sequentially.
     */
    public void printPack(ReportPack pack, ExamFlowDataSet dataSet, String sessionKey,
                          String defaultPrinter, Consumer<String> statusCallback) {
        if (pack == null || pack.getItems().isEmpty()) {
            if (statusCallback != null) statusCallback.accept("Pack has no items.");
            return;
        }

        String targetPrinter = pack.getTargetPrinter();
        if (targetPrinter == null || "Default".equalsIgnoreCase(targetPrinter) || targetPrinter.trim().isEmpty()) {
            targetPrinter = defaultPrinter;
        }

        final String resolvedPrinter = targetPrinter;
        List<ReportPackItem> items = pack.getItems();

        activityLogger.info("Starting 1-Click Pack print: '" + pack.getName() + "' (" + items.size() + " item(s)) -> " + resolvedPrinter);

        // Chain execution sequentially to prevent printer spool collision
        printPackItemIndex(pack, items, 0, dataSet, sessionKey, resolvedPrinter, statusCallback);
    }

    private void printPackItemIndex(ReportPack pack, List<ReportPackItem> items, int index,
                                    ExamFlowDataSet dataSet, String sessionKey,
                                    String resolvedPrinter, Consumer<String> statusCallback) {
        if (index >= items.size()) {
            if (statusCallback != null) statusCallback.accept("Completed Pack: " + pack.getName() + " ✔");
            activityLogger.info("Finished printing Pack: '" + pack.getName() + "'");
            return;
        }

        ReportPackItem item = items.get(index);
        if (!item.isEnabled()) {
            printPackItemIndex(pack, items, index + 1, dataSet, sessionKey, resolvedPrinter, statusCallback);
            return;
        }

        ReportType type = item.getReportType();
        if (type == null) {
            printPackItemIndex(pack, items, index + 1, dataSet, sessionKey, resolvedPrinter, statusCallback);
            return;
        }

        if (statusCallback != null) {
            statusCallback.accept("Printing [" + (index + 1) + "/" + items.size() + "] " + type.getDisplayName() + "...");
        }

        printReport(type, dataSet, sessionKey, resolvedPrinter, item.isDuplex(), item.getCopies(), status -> {
            if (status != null && status.startsWith("Printed")) {
                // Short delay before sending next job to let spooler settle
                new Thread(() -> {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> printPackItemIndex(pack, items, index + 1, dataSet, sessionKey, resolvedPrinter, statusCallback));
                }).start();
            } else if (status != null && (status.startsWith("Failed") || status.startsWith("Print Error"))) {
                if (statusCallback != null) statusCallback.accept("Pack stopped at " + type.getDisplayName() + ": " + status);
            }
        });
    }

    /**
     * Exports the report to an HTML file (or PDF) for archiving or manual review.
     */
    public void exportReportToFile(ReportType type, ExamFlowDataSet dataSet, String sessionKey, File targetFile) throws Exception {
        String html = ExamFlowReportRenderer.renderReport(type, dataSet, sessionKey);
        try (FileWriter writer = new FileWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write(html);
        }
        activityLogger.info("Exported report HTML: " + type.getDisplayName() + " -> " + targetFile.getAbsolutePath());
    }

    private Printer resolvePrinter(String printerName) {
        if (printerName != null && !printerName.trim().isEmpty() && !"Default".equalsIgnoreCase(printerName)) {
            for (Printer p : Printer.getAllPrinters()) {
                if (p.getName().equalsIgnoreCase(printerName.trim())) {
                    return p;
                }
            }
        }
        Printer def = Printer.getDefaultPrinter();
        return def != null ? def : Printer.getAllPrinters().iterator().next();
    }
}
