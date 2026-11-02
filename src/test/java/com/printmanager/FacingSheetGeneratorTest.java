package com.printmanager;

import com.printmanager.model.RoomFacingData;
import com.printmanager.model.RoomFacingData.CourseSummaryEntry;
import com.printmanager.model.RoomFacingData.StudentEntry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FacingSheetGeneratorTest {

    @Test
    public void testGenerateSigningSheetWithScribeAndCheckLayout() throws IOException {
        RoomFacingData data = new RoomFacingData();
        data.setCollegeName("GOVT. VICTORIA COLLEGE, PALAKKAD");
        data.setExamName("FOURTH SEMESTER BA/BSC/BCOM DEGREE REGULAR EXAMINATION APRIL 2026");
        data.setHallNumber("101");
        data.setRoomName("Main Block Room 1");
        data.setLocation("First Floor East Wing");
        data.setStream("CBCSS");
        data.setDate("14-05-2026");
        data.setTime("09:30 AM - 12:30 PM");
        data.setInvigilatorName("Dr. K. Narayanan");

        List<CourseSummaryEntry> summaries = new ArrayList<>();
        CourseSummaryEntry cs1 = new CourseSummaryEntry();
        cs1.setQpCode("10512");
        cs1.setCourseName("B.Sc Botany: Core Course IV - Plant Anatomy");
        cs1.setCount(15);
        cs1.setScribeCount(1);
        summaries.add(cs1);

        CourseSummaryEntry cs2 = new CourseSummaryEntry();
        cs2.setQpCode("10513");
        cs2.setCourseName("B.Sc Zoology: Core Course IV - Physiology");
        cs2.setCount(10);
        summaries.add(cs2);
        data.setCourseSummary(summaries);

        List<StudentEntry> students = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            StudentEntry s = new StudentEntry();
            s.setSeatNumber(i);
            s.setRegNo("VPAUBS" + String.format("%04d", 100 + i));
            s.setName("Student Name " + i);
            if (i <= 15) {
                s.setQpCode("10512");
                s.setCourseName("Plant Anatomy");
            } else {
                s.setQpCode("10513");
                s.setCourseName("Physiology");
            }
            if (i == 14) {
                s.setScribe(true);
                s.setRemarks("SCRIBE");
            }
            students.add(s);
        }
        data.setStudents(students);

        FacingSheetGenerator gen = new FacingSheetGenerator();
        File pdf = gen.generate(data);
        assertNotNull(pdf);
        assertTrue(pdf.exists());
        assertTrue(pdf.length() > 0);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(2, doc.getNumberOfPages());
        }

        File dest = new File("D:/smartqpprintmanager/target/test_signing_sheet_25_students.pdf");
        java.nio.file.Files.copy(pdf.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Generated test PDF at: " + dest.getAbsolutePath());
    }

    @Test
    public void testGenerateSigningSheetUnder20Students() throws IOException {
        RoomFacingData data = new RoomFacingData();
        data.setCollegeName("GOVT. VICTORIA COLLEGE, PALAKKAD");
        data.setExamName("SECOND SEMESTER MA ENGLISH DEGREE EXAMINATION");
        data.setHallNumber("205");
        data.setDate("15-05-2026");
        data.setTime("09:30 AM");
        data.setInvigilatorName("Prof. Meera Mohan");

        List<CourseSummaryEntry> summaries = new ArrayList<>();
        CourseSummaryEntry cs1 = new CourseSummaryEntry();
        cs1.setQpCode("10890");
        cs1.setCourseName("British Literature 19th Century");
        cs1.setCount(12);
        summaries.add(cs1);
        data.setCourseSummary(summaries);

        List<StudentEntry> students = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            StudentEntry s = new StudentEntry();
            s.setSeatNumber(i);
            s.setRegNo("VPAUEN" + String.format("%04d", 50 + i));
            s.setName("English Student " + i);
            s.setQpCode("10890");
            s.setCourseName("British Literature");
            students.add(s);
        }
        data.setStudents(students);

        FacingSheetGenerator gen = new FacingSheetGenerator();
        File pdf = gen.generate(data);
        assertNotNull(pdf);
        assertTrue(pdf.exists());

        File dest = new File("D:/smartqpprintmanager/target/test_signing_sheet_12_students.pdf");
        java.nio.file.Files.copy(pdf.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Generated test PDF at: " + dest.getAbsolutePath());
    }
}
