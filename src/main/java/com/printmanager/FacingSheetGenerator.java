package com.printmanager;

import com.printmanager.model.RoomFacingData;
import com.printmanager.model.RoomFacingData.CourseSummaryEntry;
import com.printmanager.model.RoomFacingData.StudentEntry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FacingSheetGenerator {
    private static final Logger logger = LoggerFactory.getLogger(FacingSheetGenerator.class);

    // Standard A4 dimensions
    private static final float PAGE_WIDTH = 594.96f;
    private static final float PAGE_HEIGHT = 841.92f;

    // Outer border margin
    private static final float OUTER_MARGIN = 28.5f;

    // Table coordinates
    private static final float TABLE_LEFT = 57.0f;
    private static final float TABLE_RIGHT = 539.25f;
    private static final float TABLE_WIDTH = TABLE_RIGHT - TABLE_LEFT; // 482.25f

    // Column widths for 6-column student table
    private static final float COL_SEAT = 24.0f;      // 57.0 -> 81.0
    private static final float COL_COURSE = 144.75f;  // 81.0 -> 225.75
    private static final float COL_REG = 72.0f;       // 225.75 -> 297.75
    private static final float COL_NAME = 120.75f;    // 297.75 -> 418.5
    private static final float COL_REMARKS = 72.0f;   // 418.5 -> 490.5
    private static final float COL_SIGN = 48.75f;     // 490.5 -> 539.25

    private final PDFont fontRegular;
    private final PDFont fontBold;
    private final PDFont fontOblique;
    private final PDFont fontBoldOblique;

    public FacingSheetGenerator() {
        this.fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        this.fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        this.fontOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        this.fontBoldOblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
    }

    /**
     * Generates a 2-page Facing Sheet PDF matching ExamFlow V12.8.5.
     */
    public File generate(RoomFacingData data) throws IOException {
        File tempFile = File.createTempFile("facing_sheet_room_" + safeFileName(data.getHallNumber()) + "_", ".pdf");
        if (tempFile.exists()) tempFile.delete();
        try (PDDocument doc = new PDDocument()) {
            addRoomFacingPages(doc, data);
            doc.save(tempFile);
        }
        return tempFile;
    }

    /**
     * Appends the 2 pages for a room into an existing PDDocument.
     * Useful for batch-exporting all rooms into a single consolidated PDF.
     */
    public void addRoomFacingPages(PDDocument doc, RoomFacingData data) throws IOException {
        PDPage p1 = new PDPage(PDRectangle.A4);
        doc.addPage(p1);
        renderPage1(doc, p1, data);

        PDPage p2 = new PDPage(PDRectangle.A4);
        doc.addPage(p2);
        renderPage2(doc, p2, data);
    }

    // =========================================================================
    // PAGE 1: Seats 1 to 20
    // =========================================================================

    private void renderPage1(PDDocument doc, PDPage page, RoomFacingData data) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // 1. Outer Border REMOVED as requested

            // 2. Top corner tags
            drawText(cs, fontBold, 11f, "Page 1", TABLE_LEFT, 50.0f);
            String stream = cleanText(data.getStream());
            if (!stream.isEmpty()) {
                float sw = getStringWidth(fontBold, 11f, stream);
                drawText(cs, fontBold, 11f, stream, TABLE_RIGHT - sw, 50.0f);
            }

            // 3. Center Headers
            String college = cleanText(data.getCollegeName()).toUpperCase();
            if (college.isEmpty()) college = "GOVT. VICTORIA COLLEGE, PALAKKAD";
            drawCenteredText(cs, fontBold, 14f, college, TABLE_LEFT, TABLE_RIGHT, 50.0f);

            String exam = cleanText(data.getExamName());
            drawCenteredText(cs, fontBold, 12f, exam, TABLE_LEFT, TABLE_RIGHT, 71.0f);

            String hallText = "Hall: " + cleanText(data.getHallNumber()) +
                    (data.getDate().isEmpty() ? "" : " | " + cleanText(data.getDate())) +
                    (data.getTime().isEmpty() ? "" : " | " + cleanText(data.getTime()));
            drawCenteredText(cs, fontBold, 12f, hallText, TABLE_LEFT, TABLE_RIGHT, 92.0f);

            String location = cleanText(data.getLocation());
            if (!location.isEmpty()) {
                drawCenteredText(cs, fontBoldOblique, 10.5f, "Location: " + location, TABLE_LEFT, TABLE_RIGHT, 110.0f);
            }

            // 4. Student Table Header
            float tableY = 124.0f;
            float headerH = 30.0f;
            drawStudentTableHeader(cs, tableY, headerH);

            // 5. Student Rows (Seats 1 to 20)
            List<StudentEntry> allStudents = data.getStudents() != null ? data.getStudents() : new ArrayList<>();
            float rowY = tableY + headerH; // 154.0f
            float rowH = 31.0f; // 20 * 31.0f = 620.0f; ends at 774.0f

            String prevCourse = null;
            String prevRegPrefix = null;

            for (int i = 0; i < 20; i++) {
                float curY = rowY + i * rowH;
                int seatIdx = i;
                StudentEntry s = seatIdx < allStudents.size() ? allStudents.get(seatIdx) : null;
                boolean isScribe = isScribeStudent(s);

                if (isScribe) {
                    fillRect(cs, TABLE_LEFT, curY, TABLE_WIDTH, rowH, 0.08f, 0.08f, 0.08f);
                    drawRect(cs, TABLE_LEFT, curY, TABLE_WIDTH, rowH, 0.75f);
                    cs.saveGraphicsState();
                    cs.setStrokingColor(1f, 1f, 1f);
                    cs.setLineWidth(0.5f);
                    drawStudentTableVerticalLines(cs, curY, rowH);
                    cs.restoreGraphicsState();
                } else {
                    drawStudentTableRowBorders(cs, curY, rowH);
                }

                if (s != null) {
                    renderStudentRowCells(cs, s, curY, rowH, prevCourse, prevRegPrefix, i == 0);
                    prevCourse = normalizeCourse(s);
                    prevRegPrefix = extractRegPrefix(s.getRegNo());
                }
            }

            // 6. Footer link
            String cont = "Continued on Page 2...";
            float cw = getStringWidth(fontRegular, 8.5f, cont);
            drawText(cs, fontRegular, 8.5f, cont, TABLE_RIGHT - cw, 796.0f);
        }
    }

    // =========================================================================
    // PAGE 2: Seats 21 to 30 + Course Summary + Accounting Box + Signature
    // =========================================================================

    private void renderPage2(PDDocument doc, PDPage page, RoomFacingData data) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // 1. Outer Border REMOVED as requested

            // 2. Page 2 Header
            String hallRoom = "Hall: " + cleanText(data.getHallNumber()) +
                    (data.getRoomName().isEmpty() ? "" : " (" + cleanText(data.getRoomName()) + ")");
            drawText(cs, fontBold, 10f, hallRoom, TABLE_LEFT, 50.0f);

            String exam = cleanText(data.getExamName());
            drawCenteredText(cs, fontBold, 10.5f, exam, TABLE_LEFT, TABLE_RIGHT, 50.0f);

            String streamTag = "Page 2" + (data.getStream().isEmpty() ? "" : " - " + cleanText(data.getStream()));
            float stw = getStringWidth(fontBold, 10f, streamTag);
            drawText(cs, fontBold, 10f, streamTag, TABLE_RIGHT - stw, 50.0f);

            // Header horizontal line
            drawLine(cs, TABLE_LEFT, 56.0f, TABLE_RIGHT, 56.0f, 0.75f);

            // 3. Seats 21 to 30 Table (or "End of Student List")
            List<StudentEntry> allStudents = data.getStudents() != null ? data.getStudents() : new ArrayList<>();
            boolean hasPage2Students = allStudents.size() > 20;
            float summaryStartY;

            if (hasPage2Students) {
                float tableY = 64.0f;
                float headerH = 28.0f;
                drawStudentTableHeader(cs, tableY, headerH);

                float rowY = tableY + headerH;
                float rowH = 28.0f;

                String prevCourse = null;
                String prevRegPrefix = null;

                for (int i = 0; i < 10; i++) {
                    float curY = rowY + i * rowH;
                    int seatIdx = 20 + i;
                    StudentEntry s = seatIdx < allStudents.size() ? allStudents.get(seatIdx) : null;
                    boolean isScribe = isScribeStudent(s);

                    if (isScribe) {
                        fillRect(cs, TABLE_LEFT, curY, TABLE_WIDTH, rowH, 0.08f, 0.08f, 0.08f);
                        drawRect(cs, TABLE_LEFT, curY, TABLE_WIDTH, rowH, 0.75f);
                        cs.saveGraphicsState();
                        cs.setStrokingColor(1f, 1f, 1f);
                        cs.setLineWidth(0.5f);
                        drawStudentTableVerticalLines(cs, curY, rowH);
                        cs.restoreGraphicsState();
                    } else {
                        drawStudentTableRowBorders(cs, curY, rowH);
                    }

                    if (s != null) {
                        renderStudentRowCells(cs, s, curY, rowH, prevCourse, prevRegPrefix, i == 0);
                        prevCourse = normalizeCourse(s);
                        prevRegPrefix = extractRegPrefix(s.getRegNo());
                    }
                }

                summaryStartY = 384.0f;
            } else {
                // <= 20 students: show (End of Student List)
                drawCenteredText(cs, fontBold, 11f, "(End of Student List)", TABLE_LEFT, TABLE_RIGHT, 80.0f);
                summaryStartY = 96.0f;
            }

            // 4. Course Summary Section
            drawText(cs, fontBold, 9.5f, "Course Summary:", TABLE_LEFT, summaryStartY + 10f);

            float csTableY = summaryStartY + 16f;
            float colQP = 77.2f;
            float colCourse = 327.8f;
            float colCount = 77.25f;

            float csHeaderH = hasPage2Students ? 18.0f : 24.0f;
            float csRowH = hasPage2Students ? 17.0f : 22.0f;
            float csFontSize = hasPage2Students ? 8.5f : 9.5f;

            // Header row
            drawRect(cs, TABLE_LEFT, csTableY, TABLE_WIDTH, csHeaderH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP, csTableY, TABLE_LEFT + colQP, csTableY + csHeaderH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP + colCourse, csTableY, TABLE_LEFT + colQP + colCourse, csTableY + csHeaderH, 0.75f);

            drawText(cs, fontBold, csFontSize, "QP Code", TABLE_LEFT + 4f, csTableY + csHeaderH - 5.5f);
            drawText(cs, fontBold, csFontSize, "Course Name", TABLE_LEFT + colQP + 4f, csTableY + csHeaderH - 5.5f);
            drawCenteredText(cs, fontBold, csFontSize, "Count", TABLE_RIGHT - colCount, TABLE_RIGHT, csTableY + csHeaderH - 5.5f);

            // Data rows
            List<CourseSummaryEntry> summaryList = data.getCourseSummary() != null ? data.getCourseSummary() : new ArrayList<>();
            float csRowY = csTableY + csHeaderH;

            for (CourseSummaryEntry cse : summaryList) {
                drawRect(cs, TABLE_LEFT, csRowY, TABLE_WIDTH, csRowH, 0.75f);
                drawLine(cs, TABLE_LEFT + colQP, csRowY, TABLE_LEFT + colQP, csRowY + csRowH, 0.75f);
                drawLine(cs, TABLE_LEFT + colQP + colCourse, csRowY, TABLE_LEFT + colQP + colCourse, csRowY + csRowH, 0.75f);

                String qp = formatQPCode(cse.getQpCode());
                drawText(cs, fontBold, csFontSize, qp, TABLE_LEFT + 4f, csRowY + csRowH - 5.5f);

                // Course Name + (X Scribes)
                String cName = !cse.getSmartCourseName().isEmpty() ? cse.getSmartCourseName() : cse.getCourseName();
                cName = cleanText(cName);
                if (cse.getScribeCount() > 0 && !cName.contains("Scribe")) {
                    cName = cName + " (" + cse.getScribeCount() + " Scribes)";
                }
                String fitName = fitText(fontRegular, csFontSize, cName, colCourse - 8f);
                drawText(cs, fontRegular, csFontSize, fitName, TABLE_LEFT + colQP + 4f, csRowY + csRowH - 5.5f);

                drawCenteredText(cs, fontBold, csFontSize, String.valueOf(cse.getCount()), TABLE_RIGHT - colCount, TABLE_RIGHT, csRowY + csRowH - 5.5f);
                csRowY += csRowH;
            }

            // Total row
            drawRect(cs, TABLE_LEFT, csRowY, TABLE_WIDTH, csRowH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP + colCourse, csRowY, TABLE_LEFT + colQP + colCourse, csRowY + csRowH, 0.75f);
            drawRightAlignedText(cs, fontBold, csFontSize, "Total (Excl. Scribes):", TABLE_LEFT + colQP + colCourse - 4f, csRowY + csRowH - 5.5f);
            int totalBooklets = data.getTotalBookletsExclScribes();
            if (totalBooklets == 0 && !summaryList.isEmpty()) {
                totalBooklets = summaryList.stream().mapToInt(CourseSummaryEntry::getCount).sum();
            }
            drawCenteredText(cs, fontBold, csFontSize, String.valueOf(totalBooklets), TABLE_RIGHT - colCount, TABLE_RIGHT, csRowY + csRowH - 5.5f);
            float csTableEnd = csRowY + csRowH;

            // 5. Invigilator Accounting Box
            float accBoxBottom = 744.0f;
            float accBoxY = Math.max(csTableEnd + 14.0f, hasPage2Students ? 482.0f : 240.0f);
            float accBoxH = accBoxBottom - accBoxY;
            drawRect(cs, TABLE_LEFT, accBoxY, TABLE_WIDTH, accBoxH, 0.75f);

            // Section 1: Booklets Received / Used / Returned
            drawText(cs, fontBold, 9.5f, "Booklets Received: __________", TABLE_LEFT + 6.0f, accBoxY + 18f);
            drawText(cs, fontBold, 9.5f, "Used: __________", TABLE_LEFT + 195.0f, accBoxY + 18f);
            drawText(cs, fontBold, 9.5f, "Balance Returned: __________", TABLE_LEFT + 325.0f, accBoxY + 18f);
            drawLine(cs, TABLE_LEFT, accBoxY + 30f, TABLE_RIGHT, accBoxY + 30f, 0.5f);

            // Section 2: Written Booklets (QP Wise)
            drawText(cs, fontBold, 9.5f, "Written Booklets (QP Wise):", TABLE_LEFT + 6.0f, accBoxY + 46f);

            List<String> qpList = data.getUniqueQPCodes();
            if (qpList == null || qpList.isEmpty()) {
                qpList = new ArrayList<>();
                for (CourseSummaryEntry c : summaryList) {
                    String q = formatQPCode(c.getQpCode());
                    if (!qpList.contains(q)) qpList.add(q);
                }
            }
            float qpX = TABLE_LEFT + 6.0f;
            float qpSpacing = 115.0f;
            float qpRowY = accBoxY + 66f;
            for (String q : qpList) {
                if (qpX + qpSpacing > TABLE_RIGHT - 10f) {
                    qpX = TABLE_LEFT + 6.0f;
                    qpRowY += 20f;
                }
                drawText(cs, fontRegular, 9f, formatQPCode(q) + ": ___________", qpX, qpRowY);
                qpX += qpSpacing;
            }

            if (!hasPage2Students) {
                // Room has <= 20 students: utilize spacious lower area with Absentee Register & Verification
                float absY = Math.max(qpRowY + 30f, accBoxY + 130f);
                drawLine(cs, TABLE_LEFT, absY, TABLE_RIGHT, absY, 0.5f);
                drawText(cs, fontBold, 9.5f, "Absentee Candidates (Register Numbers):", TABLE_LEFT + 6.0f, absY + 18f);
                drawLine(cs, TABLE_LEFT + 6.0f, absY + 40f, TABLE_RIGHT - 15f, absY + 40f, 0.5f);
                drawLine(cs, TABLE_LEFT + 6.0f, absY + 62f, TABLE_RIGHT - 15f, absY + 62f, 0.5f);
                drawLine(cs, TABLE_LEFT + 6.0f, absY + 84f, TABLE_RIGHT - 15f, absY + 84f, 0.5f);

                float certY = absY + 105.0f;
                drawLine(cs, TABLE_LEFT, certY, TABLE_RIGHT, certY, 0.5f);
                drawText(cs, fontOblique, 8.5f, "Certified that all candidate entries and booklet counts have been personally verified.", TABLE_LEFT + 6.0f, certY + 18f);
                drawText(cs, fontBold, 9.0f, "Invigilator Remarks: [  ] Nil    [  ] Other: ________________________________________________", TABLE_LEFT + 6.0f, certY + 38f);
            }

            // Section 3: Bottom row of Accounting Box
            drawLine(cs, TABLE_LEFT, accBoxBottom - 28f, TABLE_RIGHT, accBoxBottom - 28f, 0.5f);
            drawText(cs, fontBold, 9.5f, "Written Booklets Total: __________", TABLE_LEFT + 6.0f, accBoxBottom - 10f);
            drawRightAlignedText(cs, fontBold, 9.5f, "Unused Booklets Returned: __________", TABLE_RIGHT - 10f, accBoxBottom - 10f);

            // 6. Scribe Assistance Note (Conditional)
            float footerY = 785.0f;
            if (data.hasScribes()) {
                drawText(cs, fontOblique, 9f, "* = Scribe Assistance", TABLE_LEFT, footerY);
            }

            // 7. Invigilator Signature Line & Name
            float sigLineW = 160f;
            float sigLineX1 = TABLE_RIGHT - sigLineW;
            float sigLineX2 = TABLE_RIGHT;
            drawLine(cs, sigLineX1, 765.0f, sigLineX2, 765.0f, 0.75f);
            drawCenteredText(cs, fontBold, 9f, "Invigilator's Signature", sigLineX1, sigLineX2, 778.0f);

            String invigilator = cleanText(data.getInvigilatorName());
            if (!invigilator.isEmpty()) {
                drawCenteredText(cs, fontRegular, 8.5f, invigilator, sigLineX1, sigLineX2, 792.0f);
            }
        }
    }

    // =========================================================================
    // Table Helper Methods
    // =========================================================================

    private void drawStudentTableHeader(PDPageContentStream cs, float y, float h) throws IOException {
        drawRect(cs, TABLE_LEFT, y, TABLE_WIDTH, h, 0.75f);
        drawStudentTableVerticalLines(cs, y, h);

        float midBaseline = y + (h * 0.65f);
        drawText(cs, fontBold, 8.5f, "Seat", 59.8f, midBaseline);
        drawText(cs, fontBold, 8.5f, "Course (QP Code)", 83.9f, midBaseline);

        float regColRight = TABLE_LEFT + COL_SEAT + COL_COURSE + COL_REG;
        drawRightAlignedText(cs, fontBold, 8.5f, "Register", regColRight - 2.5f, y + (h * 0.40f));
        drawRightAlignedText(cs, fontBold, 8.5f, "Number", regColRight - 2.5f, y + (h * 0.78f));

        drawText(cs, fontBold, 8.5f, "Name", 300.7f, midBaseline);
        drawText(cs, fontBold, 8.5f, "Remarks", 421.2f, midBaseline);
        drawText(cs, fontBold, 8.5f, "Sign", 493.5f, midBaseline);
    }

    private void drawStudentTableRowBorders(PDPageContentStream cs, float y, float h) throws IOException {
        drawRect(cs, TABLE_LEFT, y, TABLE_WIDTH, h, 0.75f);
        drawStudentTableVerticalLines(cs, y, h);
    }

    private void drawStudentTableVerticalLines(PDPageContentStream cs, float y, float h) throws IOException {
        float x1 = TABLE_LEFT + COL_SEAT;
        float x2 = x1 + COL_COURSE;
        float x3 = x2 + COL_REG;
        float x4 = x3 + COL_NAME;
        float x5 = x4 + COL_REMARKS;

        drawLine(cs, x1, y, x1, y + h, 0.75f);
        drawLine(cs, x2, y, x2, y + h, 0.75f);
        drawLine(cs, x3, y, x3, y + h, 0.75f);
        drawLine(cs, x4, y, x4, y + h, 0.75f);
        drawLine(cs, x5, y, x5, y + h, 0.75f);
    }

    private void renderStudentRowCells(PDPageContentStream cs, StudentEntry s, float y, float h,
                                       String prevCourse, String prevRegPrefix, boolean isFirstOnPage) throws IOException {
        boolean isScribe = isScribeStudent(s);
        float baseLine = y + (h * 0.65f);

        cs.saveGraphicsState();
        if (isScribe) {
            cs.setNonStrokingColor(1f, 1f, 1f); // Inverted white font on dark background
        }

        // 1. Seat
        String seatStr = String.valueOf(s.getSeatNumber()) + (isScribe ? "*" : "");
        PDFont seatFont = isScribe ? fontBold : fontRegular;
        drawText(cs, seatFont, 8.5f, seatStr, 60.6f, baseLine);

        // 2. Course (QP Code)
        float courseX = 84.7f;
        String qp = formatQPCode(s.getQpCode());
        drawText(cs, fontBold, 8.5f, qp, courseX, baseLine);

        String currentNormCourse = normalizeCourse(s);
        if (!isFirstOnPage && prevCourse != null && prevCourse.equalsIgnoreCase(currentNormCourse) && !isScribe) {
            // Consecutive matching course: display ditto mark "
            drawText(cs, fontRegular, 8.5f, " \"", courseX + 39.0f, baseLine);
        } else {
            // New course or scribe or first on page: display course name (max ~95 pt width)
            String cName = cleanText(s.getCourseName());
            PDFont cFont = isScribe ? fontBold : fontRegular;
            String fitCName = fitText(cFont, 7.2f, cName, COL_COURSE - 45f);
            drawText(cs, cFont, 7.2f, fitCName, courseX + 41.0f, baseLine);
        }

        // 3. Register Number
        float regColLeft = TABLE_LEFT + COL_SEAT + COL_COURSE;
        float regColRight = regColLeft + COL_REG;
        String curPrefix = extractRegPrefix(s.getRegNo());
        String curSuffix = extractRegSuffix(s.getRegNo());

        if (!isFirstOnPage && prevRegPrefix != null && prevRegPrefix.equalsIgnoreCase(curPrefix) && !curSuffix.isEmpty() && !isScribe) {
            // Same prefix as previous: display distinct suffix digits in 12.0pt Bold right-aligned
            drawRightAlignedText(cs, fontBold, 12f, curSuffix, regColRight - 3.0f, baseLine + 0.5f);
        } else {
            // New prefix or scribe or first on page: display full register number in 9.0pt Bold
            String reg = cleanText(s.getRegNo());
            drawText(cs, fontBold, 9f, reg, regColLeft + 3.0f, baseLine);
        }

        // 4. Name
        float nameColLeft = regColRight;
        String name = cleanText(s.getName());
        PDFont nameFont = isScribe ? fontBold : fontRegular;
        String fitName = fitText(nameFont, 8.5f, name, COL_NAME - 6f);
        drawText(cs, nameFont, 8.5f, fitName, nameColLeft + 3.5f, baseLine);

        // 5. Remarks
        float remColLeft = nameColLeft + COL_NAME;
        String remarks = isScribe ? "SCRIBE" : cleanText(s.getRemarks());
        if (!remarks.isEmpty()) {
            drawText(cs, fontBold, 8.5f, remarks, remColLeft + 3.5f, baseLine);
        }

        // 6. Sign: Blank cell
        cs.restoreGraphicsState();
    }

    private boolean isScribeStudent(StudentEntry s) {
        if (s == null) return false;
        if (s.isScribe()) return true;
        String remarks = s.getRemarks();
        return remarks != null && remarks.toUpperCase().contains("SCRIBE");
    }

    // =========================================================================
    // Text Formatting & Normalization Helpers
    // =========================================================================

    private String formatQPCode(String qp) {
        if (qp == null) return "";
        qp = qp.trim();
        if (qp.matches("\\d+")) {
            return "D" + qp;
        }
        return qp;
    }

    private String normalizeCourse(StudentEntry s) {
        if (s == null) return "";
        String qp = s.getQpCode() != null ? s.getQpCode().trim() : "";
        String course = s.getCourseName() != null ? s.getCourseName().trim() : "";
        return qp + "___" + course;
    }

    private String extractRegPrefix(String reg) {
        if (reg == null) return "";
        reg = reg.trim();
        Matcher m = Pattern.compile("^(.*?)(\\d{2,4})$").matcher(reg);
        if (m.find()) {
            return m.group(1);
        }
        return reg;
    }

    private String extractRegSuffix(String reg) {
        if (reg == null) return "";
        reg = reg.trim();
        Matcher m = Pattern.compile("^(.*?)(\\d{2,4})$").matcher(reg);
        if (m.find()) {
            return m.group(2);
        }
        return "";
    }

    private String safeFileName(String s) {
        if (s == null || s.trim().isEmpty()) return "1";
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String cleanText(String s) {
        if (s == null) return "";
        s = s.replace("\u2014", "-")
             .replace("\u2013", "-")
             .replace("\u2018", "'")
             .replace("\u2019", "'")
             .replace("\u201C", "\"")
             .replace("\u201D", "\"")
             .replace("\u2026", "...");
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else if (c >= 160 && c <= 255) {
                sb.append(c);
            } else if (c == ' ') {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private String fitText(PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        text = cleanText(text);
        if (text.isEmpty()) return "";
        float width = getStringWidth(font, fontSize, text);
        if (width <= maxWidth) return text;

        String ellipsis = "...";
        float ellWidth = getStringWidth(font, fontSize, ellipsis);
        if (ellWidth > maxWidth) return "";

        int low = 0, high = text.length();
        String best = "";
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = text.substring(0, mid) + ellipsis;
            if (getStringWidth(font, fontSize, candidate) <= maxWidth) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private float getStringWidth(PDFont font, float fontSize, String text) throws IOException {
        if (text == null || text.isEmpty()) return 0;
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    // =========================================================================
    // Low-level Top-Down Drawing Primitives
    // =========================================================================

    /**
     * Converts a top-down y coordinate into PDFBox bottom-up coordinate.
     */
    private float toPdfY(float topDownY) {
        return PAGE_HEIGHT - topDownY;
    }

    private void drawText(PDPageContentStream cs, PDFont font, float fontSize, String text, float x, float topDownBaseline) throws IOException {
        text = cleanText(text);
        if (text.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, toPdfY(topDownBaseline));
        cs.showText(text);
        cs.endText();
    }

    private void drawRightAlignedText(PDPageContentStream cs, PDFont font, float fontSize, String text, float rightX, float topDownBaseline) throws IOException {
        text = cleanText(text);
        if (text.isEmpty()) return;
        float width = getStringWidth(font, fontSize, text);
        drawText(cs, font, fontSize, text, rightX - width, topDownBaseline);
    }

    private void drawCenteredText(PDPageContentStream cs, PDFont font, float fontSize, String text, float left, float right, float topDownBaseline) throws IOException {
        text = cleanText(text);
        if (text.isEmpty()) return;
        float width = getStringWidth(font, fontSize, text);
        float x = left + ((right - left) - width) / 2f;
        drawText(cs, font, fontSize, text, x, topDownBaseline);
    }

    private void drawRect(PDPageContentStream cs, float x, float topDownY, float w, float h, float lineWidth) throws IOException {
        cs.setLineWidth(lineWidth);
        cs.addRect(x, toPdfY(topDownY + h), w, h);
        cs.stroke();
    }

    private void fillRect(PDPageContentStream cs, float x, float topDownY, float w, float h, float r, float g, float b) throws IOException {
        cs.saveGraphicsState();
        cs.setNonStrokingColor(r, g, b);
        cs.addRect(x, toPdfY(topDownY + h), w, h);
        cs.fill();
        cs.restoreGraphicsState();
    }

    private void drawLine(PDPageContentStream cs, float x1, float topDownY1, float x2, float topDownY2, float lineWidth) throws IOException {
        cs.setLineWidth(lineWidth);
        cs.moveTo(x1, toPdfY(topDownY1));
        cs.lineTo(x2, toPdfY(topDownY2));
        cs.stroke();
    }
}
