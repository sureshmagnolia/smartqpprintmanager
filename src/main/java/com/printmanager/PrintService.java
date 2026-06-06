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

    private static final int PRINTER_STATUS_OFFLINE = 0x00000080;
    private static final int PRINTER_STATUS_ERROR = 0x00000002;
    private static final int PRINTER_STATUS_PAPER_JAM = 0x00000008;
    private static final int PRINTER_STATUS_PAPER_OUT = 0x00000010;
    private static final int PRINTER_STATUS_NOT_AVAILABLE = 0x00001000;
    private static final int PRINTER_STATUS_PRINTING = 0x00000400;
    private static final int PRINTER_STATUS_PAUSED = 0x00000001;

    public Map<String, Map<String, String>> getPrintersDetailedStatus() {
        Map<String, Map<String, String>> detailedMap = new HashMap<>();
        try {
            com.sun.jna.platform.win32.Winspool.PRINTER_INFO_2[] printers = com.sun.jna.platform.win32.WinspoolUtil.getPrinterInfo2();
            if (printers == null || printers.length == 0) {
                throw new IllegalStateException("Failed to query Winspool or 0 printers returned");
            }
            for (com.sun.jna.platform.win32.Winspool.PRINTER_INFO_2 p : printers) {
                Map<String, String> data = new HashMap<>();
                data.put("name", p.pPrinterName);
                data.put("jobs", String.valueOf(p.cJobs));
                
                boolean isOffline = (p.Status & PRINTER_STATUS_OFFLINE) != 0 || 
                                    (p.Status & PRINTER_STATUS_NOT_AVAILABLE) != 0 ||
                                    (p.Status & PRINTER_STATUS_ERROR) != 0;
                
                String statusStr = isOffline ? "Offline" : "Ready";
                if ((p.Status & PRINTER_STATUS_PRINTING) != 0) statusStr = "Printing";
                if ((p.Status & PRINTER_STATUS_PAUSED) != 0) statusStr = "Paused";
                if ((p.Status & PRINTER_STATUS_PAPER_JAM) != 0) statusStr = "Jam";
                if ((p.Status & PRINTER_STATUS_PAPER_OUT) != 0) statusStr = "Out of Paper";
                
                // If it claims to be Ready but has jobs, it is printing or warming up
                if ("Ready".equals(statusStr) && p.cJobs > 0) statusStr = "Printing";

                data.put("status", statusStr);
                data.put("current", p.cJobs > 0 ? "Printing job..." : "Idle");
                
                detailedMap.put(p.pPrinterName, data);
            }
        } catch (Throwable e) {
            logger.error("JNA EnumPrinters failed, falling back to basic javax.print", e);
            try {
                for (javax.print.PrintService ps : java.awt.print.PrinterJob.lookupPrintServices()) {
                    Map<String, String> data = new HashMap<>();
                    data.put("status", "Unknown");
                    data.put("jobs", "0");
                    data.put("current", "Idle");
                    detailedMap.put(ps.getName(), data);
                }
            } catch (Exception inner) {
                 logger.error("javax.print fallback failed", inner);
            }
        }
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

    public File applyTopLeftOverlay(File source, String text) throws IOException {
        File target = File.createTempFile("qp_overlay_", ".pdf");
        try (PDDocument doc = Loader.loadPDF(source)) {
            org.apache.pdfbox.pdmodel.font.PDFont font = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
            float fontSize = 12;

            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setFont(font, fontSize);
                    
                    float x = 25; // Left margin
                    float y = page.getMediaBox().getHeight() - 25; // Top margin

                    stream.newLineAtOffset(x, y);
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(target);
        }
        return target;
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
            java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
            
            // Find and set the specific printer service
            javax.print.PrintService[] services = javax.print.PrintServiceLookup.lookupPrintServices(null, null);
            javax.print.PrintService selectedService = null;
            for (javax.print.PrintService service : services) {
                if (service.getName().equalsIgnoreCase(printerName)) {
                    selectedService = service;
                    break;
                }
            }

            if (selectedService == null) {
                throw new PrinterException("Printer not found: " + printerName);
            }
            job.setPrintService(selectedService);

            // Configure Print Attributes
            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
            attributes.add(new javax.print.attribute.standard.Copies(copies));
            attributes.add(new JobName(item.getFileName(), null));
            attributes.add(javax.print.attribute.standard.PrintQuality.HIGH);

            // FORCE Paper Size Selection with specific printable area to nudge smart printers
            if ("A3".equalsIgnoreCase(paperSize)) {
                attributes.add(javax.print.attribute.standard.MediaSizeName.ISO_A3);
                // A3 dimensions: 297 x 420 mm
                attributes.add(new javax.print.attribute.standard.MediaPrintableArea(5, 5, 287, 410, javax.print.attribute.standard.MediaPrintableArea.MM));
            } else {
                attributes.add(javax.print.attribute.standard.MediaSizeName.ISO_A4);
                // A4 dimensions: 210 x 297 mm
                attributes.add(new javax.print.attribute.standard.MediaPrintableArea(5, 5, 200, 287, javax.print.attribute.standard.MediaPrintableArea.MM));
            }

            if (booklet) {
                attributes.add(Sides.TWO_SIDED_SHORT_EDGE);
                attributes.add(OrientationRequested.LANDSCAPE);
            } else if (duplex) {
                attributes.add(Sides.TWO_SIDED_LONG_EDGE);
                attributes.add(OrientationRequested.PORTRAIT);
            } else {
                attributes.add(Sides.ONE_SIDED);
                attributes.add(OrientationRequested.PORTRAIT);
            }

            // CRITICAL: Derive the PageFormat from the hardware attributes to force compliance
            java.awt.print.PageFormat pf = job.getPageFormat(attributes);
            
            // Use PDFPrintable with Scaling to handle internal PDF dimension mismatches (Scale to Fit)
            org.apache.pdfbox.printing.PDFPrintable printable = new org.apache.pdfbox.printing.PDFPrintable(document, org.apache.pdfbox.printing.Scaling.SHRINK_TO_FIT);
            
            // Create a Book to wrap the printable with the forced PageFormat
            java.awt.print.Book book = new java.awt.print.Book();
            book.append(printable, pf, document.getNumberOfPages());
            job.setPageable(book);

            statusCallback.accept("Sent ✓");
            
            // Execute the print job
            job.print(attributes);
            
            statusCallback.accept("Finished ✔");

        } catch (Exception e) {
            logger.error("Print Error: ", e);
            throw new PrinterException(e.getMessage());
        } finally {
            for (File tf : tempFiles) {
                if (tf.exists()) tf.delete();
            }
        }
    }
}
