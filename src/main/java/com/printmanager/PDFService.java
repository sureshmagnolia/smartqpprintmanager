package com.printmanager;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PDFService {
    private static final Logger logger = LoggerFactory.getLogger(PDFService.class);

    public int getPageCount(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            return document.getNumberOfPages();
        }
    }

    public String getText(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            return stripper.getText(document);
        }
    }

    public java.awt.image.BufferedImage renderPage(File file, int pageIndex, float scale) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            return renderer.renderImage(pageIndex, scale);
        }
    }

    public int findKeywordPage(File file, String keyword) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                if (text.toLowerCase().contains(keyword.toLowerCase())) {
                    return i;
                }
            }
        }
        return -1;
    }

    public File splitPages(File file, int startPage, int endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDDocument newDoc = new PDDocument();
            for (int i = startPage - 1; i < endPage; i++) {
                newDoc.addPage(document.getPage(i));
            }
            File tempFile = File.createTempFile("split_", ".pdf");
            newDoc.save(tempFile);
            newDoc.close();
            return tempFile;
        }
    }

    public List<File> splitOnKeyword(File file, String keyword) throws IOException {
        List<File> splitFiles = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) {
            splitFiles.add(file);
            return splitFiles;
        }

        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();

            int startPage = 1;
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);

                if (pageText.toLowerCase().contains(keyword.toLowerCase()) && i > startPage) {
                    splitFiles.add(saveSplit(document, startPage, i - 1));
                    startPage = i;
                }
            }
            splitFiles.add(saveSplit(document, startPage, pageCount));
        }
        return splitFiles;
    }

    private File saveSplit(PDDocument srcDoc, int start, int end) throws IOException {
        try (PDDocument newDoc = new PDDocument()) {
            for (int i = start - 1; i < end; i++) {
                newDoc.addPage(srcDoc.getPage(i));
            }
            File tempFile = File.createTempFile("chunk_", ".pdf");
            newDoc.save(tempFile);
            return tempFile;
        }
    }

    public File removePages(File file, int startPage, int endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDDocument newDoc = new PDDocument();
            int total = document.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                if (i < startPage || i > endPage) {
                    newDoc.importPage(document.getPage(i - 1));
                }
            }
            if (newDoc.getNumberOfPages() == 0) {
                newDoc.close();
                return null;
            }
            File tempFile = File.createTempFile("remaining_", ".pdf");
            newDoc.save(tempFile);
            newDoc.close();
            return tempFile;
        }
    }

    public File createBookletPDF(File inputFile, String bindingType, String paperSize) throws IOException {
        logger.info("Creating Adobe-standard vector booklet for: {} (Paper: {})", inputFile.getName(), paperSize);
        try (PDDocument srcDoc = Loader.loadPDF(inputFile);
             PDDocument bookletDoc = new PDDocument()) {

            int originalPageCount = srcDoc.getNumberOfPages();
            int totalPages = (int) (Math.ceil(originalPageCount / 4.0) * 4);

            LayerUtility layerUtility = new LayerUtility(bookletDoc);
            int totalSheets = totalPages / 4;

            PDRectangle targetSheetSize = "A3".equalsIgnoreCase(paperSize) ? PDRectangle.A3 : PDRectangle.A4;

            for (int s = 0; s < totalSheets; s++) {
                // Front Side
                int frontLeftIndex = totalPages - (2 * s);
                int frontRightIndex = (2 * s) + 1;
                bookletDoc.addPage(createLandscapeSheet(srcDoc, layerUtility, frontLeftIndex, frontRightIndex, originalPageCount, targetSheetSize));

                // Back Side
                int backLeftIndex = (2 * s) + 2;
                int backRightIndex = totalPages - (2 * s) - 1;
                bookletDoc.addPage(createLandscapeSheet(srcDoc, layerUtility, backLeftIndex, backRightIndex, originalPageCount, targetSheetSize));
            }

            File tempFile = File.createTempFile("booklet_", ".pdf");
            if (tempFile.exists()) tempFile.delete();
            bookletDoc.save(tempFile);
            return tempFile;
        }
    }

    private PDPage createLandscapeSheet(PDDocument srcDoc, LayerUtility layerUtility,
                                               int leftIdx, int rightIdx, int maxPages, PDRectangle baseSize) throws IOException {
        PDPage sheet = new PDPage(new PDRectangle(baseSize.getHeight(), baseSize.getWidth()));
        float sheetH = sheet.getMediaBox().getHeight();
        float halfW = sheet.getMediaBox().getWidth() / 2.0f;

        try (PDPageContentStream contentStream = new PDPageContentStream(layerUtility.getDocument(), sheet)) {
            if (leftIdx <= maxPages) {
                drawPageVector(srcDoc, layerUtility, contentStream, leftIdx - 1, 0, 0, halfW, sheetH);
            }
            if (rightIdx <= maxPages) {
                drawPageVector(srcDoc, layerUtility, contentStream, rightIdx - 1, halfW, 0, halfW, sheetH);
            }
        }
        return sheet;
    }

    private void drawPageVector(PDDocument srcDoc, LayerUtility layerUtility, PDPageContentStream stream,
                                     int srcPageIdx, float tx, float ty, float targetW, float targetH) throws IOException {

        PDFormXObject form = layerUtility.importPageAsForm(srcDoc, srcPageIdx);

        PDRectangle sourceBox = srcDoc.getPage(srcPageIdx).getMediaBox();
        float fw = sourceBox.getWidth();
        float fh = sourceBox.getHeight();

        float scale = Math.min(targetW / fw, targetH / fh);

        float offsetX = tx + (targetW - (fw * scale)) / 2.0f;
        float offsetY = ty + (targetH - (fh * scale)) / 2.0f;

        stream.saveGraphicsState();
        Matrix matrix = new Matrix();
        matrix.translate(offsetX, offsetY);
        matrix.scale(scale, scale);
        matrix.translate(-sourceBox.getLowerLeftX(), -sourceBox.getLowerLeftY());

        stream.transform(matrix);
        stream.drawForm(form);
        stream.restoreGraphicsState();
    }
}
