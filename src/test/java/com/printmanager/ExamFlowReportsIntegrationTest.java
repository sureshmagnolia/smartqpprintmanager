package com.printmanager;

import com.printmanager.reports.model.ExamFlowDataSet;
import com.printmanager.reports.model.ReportPack;
import com.printmanager.reports.model.ReportType;
import com.printmanager.reports.service.ActiveSessionResolver;
import com.printmanager.reports.service.ExamFlowDataBridge;
import com.printmanager.reports.service.ExamFlowReportRenderer;
import com.printmanager.reports.service.ReportPackManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExamFlowReportsIntegrationTest {

    @Test
    public void testPacksManagement() {
        ReportPackManager manager = new ReportPackManager();
        List<ReportPack> packs = manager.getPacks();
        assertNotNull(packs);
        assertTrue(packs.size() >= 4, "Should have at least 4 default packs");

        ReportPack preExam = manager.getPack("pre_exam_pack");
        assertNotNull(preExam);
        assertEquals("Pre-Exam Pack (QP & Hall Prep)", preExam.getName());
        assertEquals(3, preExam.getItems().size());

        // Test custom pack creation
        ReportPack custom = new ReportPack("test_custom_pack", "Test Custom", "Custom description", "Default", null);
        custom.getItems().add(new com.printmanager.reports.model.ReportPackItem("qpaper_report", true, 2, false));
        manager.savePack(custom);

        ReportPack fetched = manager.getPack("test_custom_pack");
        assertNotNull(fetched);
        assertEquals("Test Custom", fetched.getName());
        assertEquals(1, fetched.getItems().size());
        assertEquals(2, fetched.getItems().get(0).getCopies());

        // Clean up
        manager.deletePack("test_custom_pack");
        assertNull(manager.getPack("test_custom_pack"));
    }

    @Test
    public void testExamFlowDataParsingAndRendering() throws Exception {
        File backupFile = new File("C:/Users/sures/Downloads/ExamFlow_Full_Backup_2026-11-03.json");
        if (!backupFile.exists()) {
            System.out.println("Skipping file test: backup file not found at " + backupFile);
            return;
        }

        ExamFlowDataSet dataSet = ExamFlowDataBridge.loadFromFile(backupFile);
        assertNotNull(dataSet);
        assertEquals("Govt. Victoria College, Palakkad", dataSet.getCollegeName());
        assertFalse(dataSet.getAllSessions().isEmpty());

        String sessionKey = dataSet.getAllSessions().get(0);
        assertNotNull(sessionKey);

        // Test rendering for all 11 reports
        for (ReportType type : ReportType.values()) {
            String html = ExamFlowReportRenderer.renderReport(type, dataSet, sessionKey);
            assertNotNull(html, "HTML for " + type.getDisplayName() + " should not be null");
            assertTrue(html.contains("Govt. Victoria College, Palakkad"), "Should include college name in " + type.getDisplayName());
            assertTrue(html.contains("<table") || html.contains("print-page"), "Should contain table or page in " + type.getDisplayName());
            System.out.println("Verified render of: " + type.getDisplayName() + " (HTML length: " + html.length() + ")");
        }
    }
}
