package com.printmanager;

import com.printmanager.model.FileItem;
import java.io.File;
import java.util.List;

public class TestPDFService {
    public static void main(String[] args) {
        PDFService pdfService = new PDFService();
        File dummyFile = new File("non_existent.pdf");
        System.out.println("Testing PDFService with non-existent file...");
        try {
            pdfService.getPageCount(dummyFile);
        } catch (Exception e) {
            System.out.println("Caught expected exception: " + e.getMessage());
        }
        
        System.out.println("PDFService test complete.");
    }
}
