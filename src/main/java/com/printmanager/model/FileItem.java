package com.printmanager.model;

import javafx.beans.property.*;
import java.io.File;

public class FileItem {
    private final File file;
    private final StringProperty fileName;
    private final IntegerProperty pageCount;
    private final StringProperty targetPrinter;
    private final StringProperty status;
    private final BooleanProperty duplex;
    private final BooleanProperty booklet;
    private final StringProperty bindingType;
    private final StringProperty style; // "Simplex", "Duplex", "Booklet"
    private final IntegerProperty copies;
    private final StringProperty paperSize; // "A4", "A3"
    private final StringProperty overlayText;

    private final String content;

    public FileItem(File file, int pageCount, String content, String targetPrinter, boolean duplex, boolean booklet, String bindingType, int copies, String paperSize, String overlayText) {
        this.file = file;
        this.fileName = new SimpleStringProperty(file.getName());
        this.pageCount = new SimpleIntegerProperty(pageCount);
        this.content = content;
        this.targetPrinter = new SimpleStringProperty(targetPrinter);
        this.status = new SimpleStringProperty("Ready");
        this.duplex = new SimpleBooleanProperty(duplex);
        this.booklet = new SimpleBooleanProperty(booklet);
        this.bindingType = new SimpleStringProperty(bindingType);
        this.copies = new SimpleIntegerProperty(copies);
        this.paperSize = new SimpleStringProperty(paperSize != null ? paperSize : "A4");
        this.overlayText = new SimpleStringProperty(overlayText != null ? overlayText : "");

        String initialStyle = "Simplex";
        if (booklet) initialStyle = "Booklet";
        else if (duplex) initialStyle = "Duplex";
        this.style = new SimpleStringProperty(initialStyle);

        // Sync style with flags
        this.style.addListener((obs, oldV, newV) -> {
            if ("Booklet".equals(newV)) {
                this.booklet.set(true);
                this.duplex.set(false);
            } else if ("Duplex".equals(newV)) {
                this.booklet.set(false);
                this.duplex.set(true);
            } else {
                this.booklet.set(false);
                this.duplex.set(false);
            }
        });
    }

    public File getFile() { return file; }
    public String getFileName() { return fileName.get(); }
    public StringProperty fileNameProperty() { return fileName; }
    public void setFileName(String name) { this.fileName.set(name); }
    public int getPageCount() { return pageCount.get(); }
    public IntegerProperty pageCountProperty() { return pageCount; }
    public String getContent() { return content; }
    public String getTargetPrinter() { return targetPrinter.get(); }
    public StringProperty targetPrinterProperty() { return targetPrinter; }
    public void setTargetPrinter(String printer) { this.targetPrinter.set(printer); }
    public String getStatus() { return status.get(); }
    public StringProperty statusProperty() { return status; }
    public void setStatus(String status) { this.status.set(status); }
    public boolean isDuplex() { return duplex.get(); }
    public BooleanProperty duplexProperty() { return duplex; }
    public boolean isBooklet() { return booklet.get(); }
    public BooleanProperty bookletProperty() { return booklet; }
    public String getBindingType() { return bindingType.get(); }
    public StringProperty bindingTypeProperty() { return bindingType; }
    public void setBindingType(String bindingType) { this.bindingType.set(bindingType); }
    public String getStyle() { return style.get(); }
    public StringProperty styleProperty() { return style; }
    public void setStyle(String style) { this.style.set(style); }
    public int getCopies() { return copies.get(); }
    public IntegerProperty copiesProperty() { return copies; }
    public void setCopies(int copies) { this.copies.set(copies); }
    public String getPaperSize() { return paperSize.get(); }
    public StringProperty paperSizeProperty() { return paperSize; }
    public void setPaperSize(String paperSize) { this.paperSize.set(paperSize); }
    public String getOverlayText() { return overlayText.get(); }
    public StringProperty overlayTextProperty() { return overlayText; }
    public void setOverlayText(String text) { this.overlayText.set(text); }
    }