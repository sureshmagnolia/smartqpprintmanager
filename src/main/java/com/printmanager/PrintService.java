package com.printmanager;

import com.printmanager.model.FileItem;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Sides;
import javax.print.attribute.standard.OrientationRequested;
import javax.print.attribute.standard.JobName;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrintService {
    private static final Logger logger = LoggerFactory.getLogger(PrintService.class);

    public List<String> getAvailablePrinters() {
        List<String> printerNames = new ArrayList<>();
        javax.print.PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (javax.print.PrintService service : services) {
            printerNames.add(service.getName());
        }
        return printerNames;
    }

    public String getDefaultPrinterName() {
        javax.print.PrintService defaultService = PrintServiceLookup.lookupDefaultPrintService();
        return (defaultService != null) ? defaultService.getName() : "None";
    }

    public Map<String, Map<String, String>> getPrintersDetailedStatus() {
        Map<String, Map<String, String>> detailedMap = new HashMap<>();
        try {
            // Using Get-CimInstance with .NET Ping for robust reachability check on PS 5.1
            String script = 
                "Get-CimInstance -ClassName Win32_Printer | Select-Object Name, PrinterStatus, WorkOffline, PortName | ForEach-Object { " +
                "  $isOffline = $_.WorkOffline; " +
                "  if (-not $isOffline -and ($_.PortName -like 'IP_*' -or $_.PortName -match '^\\d+\\.\\d+\\.\\d+\\.\\d+$')) { " +
                "    $ip = $_.PortName -replace 'IP_', ''; " +
                "    try { " +
                "      $ping = New-Object System.Net.NetworkInformation.Ping; " +
                "      $reply = $ping.Send($ip, 1000); " +
                "      if ($reply.Status -ne 'Success') { $isOffline = $true } " +
                "    } catch { $isOffline = $true } " +
                "  }; " +
                "  [PSCustomObject]@{ " +
                "    Name = $_.Name; " +
                "    Status = $_.PrinterStatus; " +
                "    Offline = $isOffline " +
                "  } " +
                "} | ConvertTo-Json";

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script);
            Process p = pb.start();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode printers = mapper.readTree(p.getInputStream());
            
            Consumer<com.fasterxml.jackson.databind.JsonNode> initNode = node -> {
                String name = node.path("Name").asText();
                if (name.isEmpty()) return;
                Map<String, String> data = new HashMap<>();
                int statusInt = node.path("Status").asInt(0);
                boolean isOffline = node.path("Offline").asBoolean(false);
                
                // Detailed Status check
                String statusStr = isOffline ? "Offline" : (statusInt == 3 ? "Ready" : "Other");
                if (statusInt == 4) statusStr = "Printing";
                if (statusInt == 7) statusStr = "Offline";
                
                data.put("status", statusStr);
                data.put("jobs", "0");
                data.put("current", "Idle");
                detailedMap.put(name, data);
            };

            if (printers.isArray()) for (com.fasterxml.jackson.databind.JsonNode n : printers) initNode.accept(n);
            else if (printers.isObject()) initNode.accept(printers);

            // Fetch Active Job Details
            ProcessBuilder pbJobs = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", 
                "Get-CimInstance -ClassName Win32_PrintJob | Select-Object Name, Document | ConvertTo-Json");
            Process pJobs = pbJobs.start();
            com.fasterxml.jackson.databind.JsonNode jobs = mapper.readTree(pJobs.getInputStream());

            Consumer<com.fasterxml.jackson.databind.JsonNode> processJob = node -> {
                String fullName = node.path("Name").asText();
                if (fullName.contains(",")) {
                    String printerName = fullName.substring(0, fullName.lastIndexOf(",")).trim();
                    if (detailedMap.containsKey(printerName)) {
                        Map<String, String> data = detailedMap.get(printerName);
                        int count = Integer.parseInt(data.get("jobs")) + 1;
                        data.put("jobs", String.valueOf(count));
                        data.put("current", node.path("Document").asText("Unknown"));
                        if (!"Offline".equals(data.get("status"))) data.put("status", "Printing");
                    }
                }
            };

            if (jobs.isArray()) for (com.fasterxml.jackson.databind.JsonNode j : jobs) processJob.accept(j);
            else if (jobs.isObject()) processJob.accept(jobs);

        } catch (Exception e) { logger.error("Detailed status error", e); }
        return detailedMap;
    }

    private String parseStatus(int statusInt, boolean offline) {
        if (offline) return "Offline";
        switch (statusInt) {
            case 3: return "Ready";
            case 4: return "Printing";
            case 5: return "Warmup";
            case 1: return "Other";
            case 2: return "Unknown";
            case 7: return "Offline";
            default: return "Ready";
        }
    }

    public Map<String, String> getPrintersStatus() {
        Map<String, String> statusMap = new HashMap<>();
        Map<String, Map<String, String>> detailed = getPrintersDetailedStatus();
        detailed.forEach((k, v) -> statusMap.put(k, v.get("status")));
        return statusMap;
    }

    public File applyOverlayInternal(File source, String text) throws IOException {
        File target = File.createTempFile("overlay_", ".pdf");
        try (PDDocument doc = Loader.loadPDF(source)) {
            org.apache.pdfbox.pdmodel.font.PDFont font = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
            float fontSize = 14;

            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setFont(font, fontSize);
                    
                    float titleWidth = font.getStringWidth(text) / 1000 * fontSize;
                    float x = (page.getMediaBox().getWidth() - titleWidth) / 2;
                    float y = page.getMediaBox().getHeight() - 30; // 30 units from top

                    stream.newLineAtOffset(x, y);
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(target);
        }
        return target;
    }

    private boolean simulationMode = false;
    public boolean isSimulationMode() { return simulationMode; }
    public void setSimulationMode(boolean simulationMode) { this.simulationMode = simulationMode; }

    public void printPDF(FileItem item, Consumer<String> statusCallback) throws IOException, PrinterException {
        String printerName = item.getTargetPrinter();
        boolean duplex = item.isDuplex();
        boolean booklet = item.isBooklet();
        int copies = item.getCopies();
        String paperSize = item.getPaperSize();
        String overlayText = item.getOverlayText();
        String bindingType = item.getBindingType();

        logger.info("Direct-to-Hardware print: {} -> {} (copies: {}, duplex: {}, booklet: {}, paper: {}, overlay: {})",
                    item.getFileName(), printerName, copies, duplex, booklet, paperSize, overlayText);

        if (simulationMode) {
            logger.info("SIMULATION: Printing {} to {}", item.getFileName(), printerName);
            new Thread(() -> {
                try {
                    statusCallback.accept("Simulating...");
                    Thread.sleep(2000);
                    statusCallback.accept("Finished ✔");
                } catch (InterruptedException e) {}
            }).start();
            return;
        }

        PDFService pdfService = new PDFService();
        File processedFile = item.getFile();
        List<File> tempFiles = new ArrayList<>();

        // 1. Apply overlay if needed (on individual pages)
        if (overlayText != null && !overlayText.isEmpty()) {
            try {
                File overlayFile = applyOverlayInternal(processedFile, overlayText);
                tempFiles.add(overlayFile);
                processedFile = overlayFile;
            } catch (Exception e) {
                logger.error("Failed to apply overlay: {}", e.getMessage());
            }
        }

        // 2. Generate booklet if requested
        if (booklet) {
            try {
                File bookletFile = pdfService.createBookletPDF(processedFile, bindingType, paperSize);
                tempFiles.add(bookletFile);
                processedFile = bookletFile;
            } catch (Exception e) {
                logger.error("Failed to create booklet: {}", e.getMessage());
            }
        }

        try (PDDocument document = Loader.loadPDF(processedFile)) {
            javax.print.PrintService selectedService = null;
            javax.print.PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            for (javax.print.PrintService service : services) {
                if (service.getName().equalsIgnoreCase(printerName)) {
                    selectedService = service;
                    break;
                }
            }

            if (selectedService == null) {
                throw new PrinterException("Printer not found: " + printerName);
            }

            DocPrintJob job = selectedService.createPrintJob();
            
            job.addPrintJobListener(new PrintJobAdapter() {
                @Override
                public void printDataTransferCompleted(PrintJobEvent pje) {
                    statusCallback.accept("Sent ✓");
                }

                @Override
                public void printJobCompleted(PrintJobEvent pje) {
                    statusCallback.accept("Finished ✔");
                }

                @Override
                public void printJobFailed(PrintJobEvent pje) {
                    statusCallback.accept("Error: Failed");
                }

                @Override
                public void printJobCanceled(PrintJobEvent pje) {
                    statusCallback.accept("Canceled");
                }

                @Override
                public void printJobNoMoreEvents(PrintJobEvent pje) {
                    // Fallback for drivers that don't support printJobCompleted
                    statusCallback.accept("Finished ✔");
                }
            });

            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
            attributes.add(new javax.print.attribute.standard.Copies(copies));
            attributes.add(new JobName(item.getFileName(), null));
            attributes.add(javax.print.attribute.standard.PrintQuality.HIGH);

            if ("A3".equalsIgnoreCase(paperSize)) {
                attributes.add(javax.print.attribute.standard.MediaSizeName.ISO_A3);
            } else {
                attributes.add(javax.print.attribute.standard.MediaSizeName.ISO_A4);
            }

            if (booklet) {
                // For booklets, the sheets are already generated in landscape by PDFService
                attributes.add(Sides.TWO_SIDED_SHORT_EDGE);
                attributes.add(OrientationRequested.LANDSCAPE);
            } else if (duplex) {
                attributes.add(Sides.TWO_SIDED_LONG_EDGE);
                attributes.add(OrientationRequested.PORTRAIT);
            } else {
                attributes.add(Sides.ONE_SIDED);
                attributes.add(OrientationRequested.PORTRAIT);
            }

            // Use PDFPageable with high-quality settings
            PDFPageable pageable = new PDFPageable(document);
            Doc doc = new SimpleDoc(pageable, DocFlavor.SERVICE_FORMATTED.PAGEABLE, null);
            job.print(doc, attributes);

        } catch (PrintException e) {
            throw new PrinterException(e.getMessage());
        } finally {
            for (File tf : tempFiles) {
                if (tf.exists()) tf.delete();
            }
        }
    }
}
