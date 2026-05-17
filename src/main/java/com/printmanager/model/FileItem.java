package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.*;
import java.io.File;

public class FileItem {
    private File file;
    private StringProperty fileName = new SimpleStringProperty();
    private IntegerProperty pageCount = new SimpleIntegerProperty();
    private StringProperty targetPrinter = new SimpleStringProperty();
    private StringProperty status = new SimpleStringProperty();
    private BooleanProperty duplex = new SimpleBooleanProperty();
    private BooleanProperty booklet = new SimpleBooleanProperty();
    private StringProperty bindingType = new SimpleStringProperty();
    private StringProperty style = new SimpleStringProperty(); // "Simplex", "Duplex", "Booklet"
    private IntegerProperty copies = new SimpleIntegerProperty();
    private StringProperty paperSize = new SimpleStringProperty(); // "A4", "A3"
    private StringProperty overlayText = new SimpleStringProperty();

    private String content;

    public FileItem() {
        setupListeners();
    }

    @JsonCreator
    public FileItem(
            @JsonProperty("file") File file,
            @JsonProperty("pageCount") int pageCount,
            @JsonProperty("content") String content,
            @JsonProperty("targetPrinter") String targetPrinter,
            @JsonProperty("duplex") boolean duplex,
            @JsonProperty("booklet") boolean booklet,
            @JsonProperty("bindingType") String bindingType,
            @JsonProperty("copies") int copies,
            @JsonProperty("paperSize") String paperSize,
            @JsonProperty("overlayText") String overlayText) {
        this.file = file;
        this.fileName.set(file != null ? file.getName() : "");
        this.pageCount.set(pageCount);
        this.content = content;
        this.targetPrinter.set(targetPrinter);
        this.status.set("Ready");
        this.duplex.set(duplex);
        this.booklet.set(booklet);
        this.bindingType.set(bindingType);
        this.copies.set(copies);
        this.paperSize.set(paperSize != null ? paperSize : "A4");
        this.overlayText.set(overlayText != null ? overlayText : "");

        String initialStyle = "Simplex";
        if (booklet) initialStyle = "Booklet";
        else if (duplex) initialStyle = "Duplex";
        this.style.set(initialStyle);

        setupListeners();
    }

    private void setupListeners() {
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
    public void setFile(File file) { this.file = file; if (file != null) this.fileName.set(file.getName()); }

    @JsonProperty("fileName")
    public String getFileName() { return fileName.get(); }
    public void setFileName(String name) { this.fileName.set(name); }
    @JsonIgnore
    public StringProperty fileNameProperty() { return fileName; }

    @JsonProperty("pageCount")
    public int getPageCount() { return pageCount.get(); }
    public void setPageCount(int pageCount) { this.pageCount.set(pageCount); }
    @JsonIgnore
    public IntegerProperty pageCountProperty() { return pageCount; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    @JsonProperty("targetPrinter")
    public String getTargetPrinter() { return targetPrinter.get(); }
    public void setTargetPrinter(String printer) { this.targetPrinter.set(printer); }
    @JsonIgnore
    public StringProperty targetPrinterProperty() { return targetPrinter; }

    @JsonProperty("status")
    public String getStatus() { return status.get(); }
    public void setStatus(String status) { this.status.set(status); }
    @JsonIgnore
    public StringProperty statusProperty() { return status; }

    @JsonProperty("duplex")
    public boolean isDuplex() { return duplex.get(); }
    public void setDuplex(boolean duplex) { this.duplex.set(duplex); }
    @JsonIgnore
    public BooleanProperty duplexProperty() { return duplex; }

    @JsonProperty("booklet")
    public boolean isBooklet() { return booklet.get(); }
    public void setBooklet(boolean booklet) { this.booklet.set(booklet); }
    @JsonIgnore
    public BooleanProperty bookletProperty() { return booklet; }

    @JsonProperty("bindingType")
    public String getBindingType() { return bindingType.get(); }
    public void setBindingType(String bindingType) { this.bindingType.set(bindingType); }
    @JsonIgnore
    public StringProperty bindingTypeProperty() { return bindingType; }

    @JsonProperty("style")
    public String getStyle() { return style.get(); }
    public void setStyle(String style) { this.style.set(style); }
    @JsonIgnore
    public StringProperty styleProperty() { return style; }

    @JsonProperty("copies")
    public int getCopies() { return copies.get(); }
    public void setCopies(int copies) { this.copies.set(copies); }
    @JsonIgnore
    public IntegerProperty copiesProperty() { return copies; }

    @JsonProperty("paperSize")
    public String getPaperSize() { return paperSize.get(); }
    public void setPaperSize(String paperSize) { this.paperSize.set(paperSize); }
    @JsonIgnore
    public StringProperty paperSizeProperty() { return paperSize; }

    @JsonProperty("overlayText")
    public String getOverlayText() { return overlayText.get(); }
    public void setOverlayText(String text) { this.overlayText.set(text); }
    @JsonIgnore
    public StringProperty overlayTextProperty() { return overlayText; }
}