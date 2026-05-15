package com.printmanager;

import com.printmanager.model.PrintRule;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDPage;
import java.io.File;

public class PrintRuleTest {

    @Test
    public void analyzeSourceFile() throws Exception {
        File file = new File("C:/Users/sures/Downloads/07 QP 01.02.21-20260511T123413Z-3-001/07 QP 01.02.21/10128_1643697882155.pdf");
        if (file.exists()) {
            try (PDDocument doc = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(doc);
                System.out.println("SOURCE_TEXT_LEN: " + text.trim().length());
                PDPage page = doc.getPage(0);
                System.out.println("SOURCE_MB: " + page.getMediaBox());
            }
        }
    }

    @Test
    public void testPageRangeMatch() {
        PrintRule rule = new PrintRule(1, 5, "Printer A", false, false, "Left", null, 1, "A4");
        assertTrue(rule.matches(1, "Some content"));
    }
}
