package com.printmanager.model;

public class PrintRule {
    private int minPages;
    private int maxPages;
    private String printerName;
    private boolean duplex;
    private boolean booklet;
    private String bindingType; // "Left", "Right"
    private String keywords; // Comma-separated keywords
    private int copies = 1;
    private String paperSize = "A4"; // "A4", "A3"

    public PrintRule() {}

    public PrintRule(int minPages, int maxPages, String printerName, boolean duplex, boolean booklet, String bindingType, String keywords, int copies, String paperSize) {
        this.minPages = minPages;
        this.maxPages = maxPages;
        this.printerName = printerName;
        this.duplex = duplex;
        this.booklet = booklet;
        this.bindingType = bindingType;
        this.keywords = keywords;
        this.copies = copies;
        this.paperSize = paperSize != null ? paperSize : "A4";
    }

    public int getMinPages() { return minPages; }
    public void setMinPages(int minPages) { this.minPages = minPages; }

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }

    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }

    public boolean isDuplex() { return duplex; }
    public void setDuplex(boolean duplex) { this.duplex = duplex; }

    public boolean isBooklet() { return booklet; }
    public void setBooklet(boolean booklet) { this.booklet = booklet; }

    public String getBindingType() { return bindingType; }
    public void setBindingType(String bindingType) { this.bindingType = bindingType; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public int getCopies() { return copies; }
    public void setCopies(int copies) { this.copies = copies; }

    public String getPaperSize() { return paperSize; }
    public void setPaperSize(String paperSize) { this.paperSize = paperSize; }

    public boolean matches(int pageCount, String content) {
        // Keyword match takes priority if defined
        if (keywords != null && !keywords.isBlank() && content != null) {
            String[] ks = keywords.split(",");
            for (String k : ks) {
                if (content.toLowerCase().contains(k.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return pageCount >= minPages && pageCount <= maxPages;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(minPages).append("-").append(maxPages).append(" pages ");
        if (keywords != null && !keywords.isBlank()) {
            sb.append("(or keywords: ").append(keywords).append(") ");
        }
        sb.append("-> ").append(printerName);
        sb.append(" [").append(paperSize).append("]");
        if (booklet) {
            sb.append(" (Booklet, ").append(bindingType).append(")");
        } else if (duplex) {
            sb.append(" (Duplex)");
        }
        return sb.toString();
    }
}