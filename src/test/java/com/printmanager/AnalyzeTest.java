package com.printmanager;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import java.io.File;

public class AnalyzeTest {
    @Test
    public void checkSourceText() throws Exception {
        File file = new File("C:/Users/sures/Downloads/07 QP 01.02.21-20260511T123413Z-3-001/07 QP 01.02.21/10128_1643697882155.pdf");
        if (!file.exists()) {
            System.out.println("SOURCE FILE NOT FOUND");
            return;
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            System.out.println("TEXT_LENGTH: " + text.trim().length());
            if (text.trim().length() > 0) {
                System.out.println("PREVIEW_TEXT: " + text.substring(0, Math.min(100, text.length())).replace("\n", " "));
            }
            
            PDPage page = doc.getPage(0);
            System.out.println("PAGE_0_MB: " + page.getMediaBox());
            System.out.println("PAGE_0_CB: " + page.getCropBox());
        }
    }
}
