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
            // 1. Outer Border
            drawRect(cs, OUTER_MARGIN, OUTER_MARGIN, PAGE_WIDTH - 2 * OUTER_MARGIN, PAGE_HEIGHT - 2 * OUTER_MARGIN, 0.75f);

            // 2. Top corner tags
            drawText(cs, fontBold, 12f, "Page 1", 56.8f, 68.0f);
            String stream = cleanText(data.getStream());
            if (!stream.isEmpty()) {
                float sw = getStringWidth(fontBold, 11f, stream);
                drawText(cs, fontBold, 11f, stream, TABLE_RIGHT - sw, 70.0f);
            }

            // 3. Center Headers
            String college = cleanText(data.getCollegeName()).toUpperCase();
            if (college.isEmpty()) college = "GOVT. VICTORIA COLLEGE, PALAKKAD";
            drawCenteredText(cs, fontBold, 15f, college, TABLE_LEFT, TABLE_RIGHT, 69.0f);

            String exam = cleanText(data.getExamName());
            drawCenteredText(cs, fontBold, 13.5f, exam, TABLE_LEFT, TABLE_RIGHT, 93.0f);

            String hallText = "Hall: " + cleanText(data.getHallNumber()) +
                    (data.getDate().isEmpty() ? "" : " | " + cleanText(data.getDate())) +
                    (data.getTime().isEmpty() ? "" : " | " + cleanText(data.getTime()));
            drawCenteredText(cs, fontBold, 13.5f, hallText, TABLE_LEFT, TABLE_RIGHT, 116.0f);

            String location = cleanText(data.getLocation());
            if (!location.isEmpty()) {
                drawCenteredText(cs, fontBoldOblique, 11.5f, "Location: " + location, TABLE_LEFT, TABLE_RIGHT, 137.0f);
            }

            // 4. Student Table Header
            float tableY = 153.8f;
            float headerH = 30.75f;
            drawStudentTableHeader(cs, tableY, headerH);

            // 5. Student Rows (Seats 1 to 20)
            List<StudentEntry> allStudents = data.getStudents() != null ? data.getStudents() : new ArrayList<>();
            float rowY = tableY + headerH;
            float rowH = 24.9f;

            String prevCourse = null;
            String prevRegPrefix = null;

            for (int i = 0; i < 20; i++) {
                float curY = rowY + i * rowH;
                int seatIdx = i;
                StudentEntry s = seatIdx < allStudents.size() ? allStudents.get(seatIdx) : null;

                // Draw cell borders
                drawStudentTableRowBorders(cs, curY, rowH);

                if (s != null) {
                    renderStudentRowCells(cs, s, curY, rowH, prevCourse, prevRegPrefix, i == 0);
                    prevCourse = normalizeCourse(s);
                    prevRegPrefix = extractRegPrefix(s.getRegNo());
                }
            }

            // 6. Footer link
            String cont = "Continued on Page 2...";
            float cw = getStringWidth(fontRegular, 8f, cont);
            drawText(cs, fontRegular, 8f, cont, TABLE_RIGHT - cw, 693.0f);
        }
    }

    // =========================================================================
    // PAGE 2: Seats 21 to 30 + Course Summary + Accounting Box + Signature
    // =========================================================================

    private void renderPage2(PDDocument doc, PDPage page, RoomFacingData data) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // 1. Outer Border
            drawRect(cs, OUTER_MARGIN, OUTER_MARGIN, PAGE_WIDTH - 2 * OUTER_MARGIN, PAGE_HEIGHT - 2 * OUTER_MARGIN, 0.75f);

            // 2. Page 2 Header
            String hallRoom = "Hall: " + cleanText(data.getHallNumber()) +
                    (data.getRoomName().isEmpty() ? "" : " (" + cleanText(data.getRoomName()) + ")");
            drawText(cs, fontBold, 10f, hallRoom, 56.8f, 69.0f);

            String exam = cleanText(data.getExamName());
            drawCenteredText(cs, fontBold, 10.5f, exam, TABLE_LEFT, TABLE_RIGHT, 69.0f);

            String streamTag = "Page 2" + (data.getStream().isEmpty() ? "" : " - " + cleanText(data.getStream()));
            float stw = getStringWidth(fontBold, 10f, streamTag);
            drawText(cs, fontBold, 10f, streamTag, TABLE_RIGHT - stw, 69.0f);

            // Header horizontal line
            drawLine(cs, TABLE_LEFT, 75.0f, TABLE_RIGHT, 75.0f, 0.75f);

            // 3. Seats 21 to 30 Table (or "End of Student List")
            List<StudentEntry> allStudents = data.getStudents() != null ? data.getStudents() : new ArrayList<>();
            float summaryStartY;

            if (allStudents.size() > 20) {
                float tableY = 79.5f;
                float headerH = 30.75f;
                drawStudentTableHeader(cs, tableY, headerH);

                float rowY = tableY + headerH;
                float rowH = 24.9f;

                String prevCourse = null;
                String prevRegPrefix = null;

                int page2Students = Math.min(10, allStudents.size() - 20);
                for (int i = 0; i < page2Students; i++) {
                    float curY = rowY + i * rowH;
                    int seatIdx = 20 + i;
                    StudentEntry s = allStudents.get(seatIdx);

                    drawStudentTableRowBorders(cs, curY, rowH);
                    renderStudentRowCells(cs, s, curY, rowH, prevCourse, prevRegPrefix, i == 0);

                    prevCourse = normalizeCourse(s);
                    prevRegPrefix = extractRegPrefix(s.getRegNo());
                }

                summaryStartY = 377.4f;
            } else {
                // <= 20 students: show (End of Student List)
                drawCenteredText(cs, fontBold, 11f, "(End of Student List)", TABLE_LEFT, TABLE_RIGHT, 115.0f);
                summaryStartY = 145.0f;
            }

            // 4. Course Summary Section
            drawText(cs, fontBold, 9f, "Course Summary:", TABLE_LEFT, summaryStartY + 8f);

            float csTableY = summaryStartY + 14f;
            float colQP = 77.2f;
            float colCourse = 327.8f;
            float colCount = 77.25f;

            // Header row
            float csHeaderH = 16.5f;
            drawRect(cs, TABLE_LEFT, csTableY, TABLE_WIDTH, csHeaderH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP, csTableY, TABLE_LEFT + colQP, csTableY + csHeaderH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP + colCourse, csTableY, TABLE_LEFT + colQP + colCourse, csTableY + csHeaderH, 0.75f);

            drawText(cs, fontBold, 8.5f, "QP Code", TABLE_LEFT + 3f, csTableY + 12f);
            drawText(cs, fontBold, 8.5f, "Course Name", TABLE_LEFT + colQP + 3f, csTableY + 12f);
            drawCenteredText(cs, fontBold, 8.5f, "Count", TABLE_RIGHT - colCount, TABLE_RIGHT, csTableY + 12f);

            // Data rows
            List<CourseSummaryEntry> summaryList = data.getCourseSummary() != null ? data.getCourseSummary() : new ArrayList<>();
            float csRowY = csTableY + csHeaderH;
            float csRowH = 15.0f;

            for (CourseSummaryEntry cse : summaryList) {
                drawRect(cs, TABLE_LEFT, csRowY, TABLE_WIDTH, csRowH, 0.75f);
                drawLine(cs, TABLE_LEFT + colQP, csRowY, TABLE_LEFT + colQP, csRowY + csRowH, 0.75f);
                drawLine(cs, TABLE_LEFT + colQP + colCourse, csRowY, TABLE_LEFT + colQP + colCourse, csRowY + csRowH, 0.75f);

                String qp = formatQPCode(cse.getQpCode());
                drawText(cs, fontBold, 8.5f, qp, TABLE_LEFT + 3f, csRowY + 11f);

                // Course Name + (X Scribes)
                String cName = !cse.getSmartCourseName().isEmpty() ? cse.getSmartCourseName() : cse.getCourseName();
                cName = cleanText(cName);
                if (cse.getScribeCount() > 0 && !cName.contains("Scribe")) {
                    cName = cName + " (" + cse.getScribeCount() + " Scribes)";
                }
                String fitName = fitText(fontRegular, 8.5f, cName, colCourse - 8f);
                drawText(cs, fontRegular, 8.5f, fitName, TABLE_LEFT + colQP + 3f, csRowY + 11f);

                drawCenteredText(cs, fontBold, 8.5f, String.valueOf(cse.getCount()), TABLE_RIGHT - colCount, TABLE_RIGHT, csRowY + 11f);
                csRowY += csRowH;
            }

            // Total row
            drawRect(cs, TABLE_LEFT, csRowY, TABLE_WIDTH, csRowH, 0.75f);
            drawLine(cs, TABLE_LEFT + colQP + colCourse, csRowY, TABLE_LEFT + colQP + colCourse, csRowY + csRowH, 0.75f);
            drawRightAlignedText(cs, fontBold, 8.5f, "Total (Excl. Scribes):", TABLE_LEFT + colQP + colCourse - 3f, csRowY + 11f);
            int totalBooklets = data.getTotalBookletsExclScribes();
            if (totalBooklets == 0 && !summaryList.isEmpty()) {
                totalBooklets = summaryList.stream().mapToInt(CourseSummaryEntry::getCount).sum();
            }
            drawCenteredText(cs, fontBold, 8.5f, String.valueOf(totalBooklets), TABLE_RIGHT - colCount, TABLE_RIGHT, csRowY + 11f);

            // 5. Invigilator Accounting Box
            float accBoxY = Math.max(csRowY + csRowH + 12f, 474.4f);
            float accBoxH = 91.5f;
            drawRect(cs, TABLE_LEFT, accBoxY, TABLE_WIDTH, accBoxH, 0.75f);

            // Line 1: Booklets Received / Used / Returned
            drawText(cs, fontBold, 9f, "Booklets Received: __________", 61.3f, accBoxY + 16f);
            drawText(cs, fontBold, 9f, "Used: __________", 261.1f, accBoxY + 16f);
            drawText(cs, fontBold, 9f, "Balance Returned: __________", 402.8f, accBoxY + 16f);

            // Line 2: Written Booklets (QP Wise)
            drawText(cs, fontBold, 9f, "Written Booklets (QP Wise):", 61.3f, accBoxY + 38f);

            // Line 3: QP codes columns
            List<String> qpList = data.getUniqueQPCodes();
            if (qpList == null || qpList.isEmpty()) {
                qpList = new ArrayList<>();
                for (CourseSummaryEntry c : summaryList) {
                    String q = formatQPCode(c.getQpCode());
                    if (!qpList.contains(q)) qpList.add(q);
                }
            }
            float qpX = 61.3f;
            float qpSpacing = 79.1f;
            float qpRowY = accBoxY + 54f;
            for (String q : qpList) {
                if (qpX + qpSpacing > TABLE_RIGHT - 6f) {
                    qpX = 61.3f;
                    qpRowY += 14f;
                }
                drawText(cs, fontRegular, 9f, formatQPCode(q) + ": ", qpX, qpRowY);
                qpX += qpSpacing;
            }

            // Line 4: Written Booklets Total
            drawRightAlignedText(cs, fontBold, 9f, "Written Booklets Total: __________", TABLE_RIGHT - 4.5f, accBoxY + 78f);

            // 6. Scribe Assistance Note (Conditional)
            float footerY = 621.0f;
            if (data.hasScribes()) {
                drawText(cs, fontOblique, 9f, "* = Scribe Assistance", 56.8f, footerY);
            }

            // 7. Invigilator Signature Line & Name
            float sigLineW = 150f;
            float sigLineX1 = TABLE_RIGHT - sigLineW;
            float sigLineX2 = TABLE_RIGHT;
            drawLine(cs, sigLineX1, 604.0f, sigLineX2, 604.0f, 0.75f);

            String invigilator = cleanText(data.getInvigilatorName());
            if (!invigilator.isEmpty()) {
                drawCenteredText(cs, fontRegular, 9f, invigilator, sigLineX1, sigLineX2, footerY);
            }
        }
    }

    // =========================================================================
    // Table Helper Methods
    // =========================================================================

    private void drawStudentTableHeader(PDPageContentStream cs, float y, float h) throws IOException {
        drawRect(cs, TABLE_LEFT, y, TABLE_WIDTH, h, 0.75f);
        drawStudentTableVerticalLines(cs, y, h);

        drawText(cs, fontBold, 8.5f, "Seat", 59.8f, y + 20.0f);
        drawText(cs, fontBold, 8.5f, "Course (QP Code)", 83.9f, y + 20.0f);

        float regColRight = TABLE_LEFT + COL_SEAT + COL_COURSE + COL_REG;
        drawRightAlignedText(cs, fontBold, 8.5f, "Register", regColRight - 2.5f, y + 13.0f);
        drawRightAlignedText(cs, fontBold, 8.5f, "Number", regColRight - 2.5f, y + 24.0f);

        drawText(cs, fontBold, 8.5f, "Name", 300.7f, y + 20.0f);
        drawText(cs, fontBold, 8.5f, "Remarks", 421.2f, y + 20.0f);
        drawText(cs, fontBold, 8.5f, "Sign", 493.5f, y + 20.0f);
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
        float baseLine = y + 17.0f;

        // 1. Seat
        String seatStr = String.valueOf(s.getSeatNumber()) + (s.isScribe() ? "*" : "");
        PDFont seatFont = s.isScribe() ? fontBold : fontRegular;
        drawText(cs, seatFont, 8.5f, seatStr, 60.6f, baseLine);

        // 2. Course (QP Code)
        float courseX = 84.7f;
        String qp = formatQPCode(s.getQpCode());
        drawText(cs, fontBold, 8.5f, qp, courseX, baseLine);

        String currentNormCourse = normalizeCourse(s);
        if (!isFirstOnPage && prevCourse != null && prevCourse.equalsIgnoreCase(currentNormCourse)) {
            // Consecutive matching course: display ditto mark "
            drawText(cs, fontRegular, 8.5f, " \"", courseX + 39.0f, baseLine);
        } else {
            // New course or first on page: display course name (max ~95 pt width)
            String cName = cleanText(s.getCourseName());
            String fitCName = fitText(fontRegular, 7.2f, cName, COL_COURSE - 45f);
            drawText(cs, fontRegular, 7.2f, fitCName, courseX + 41.0f, baseLine);
        }

        // 3. Register Number
        float regColLeft = TABLE_LEFT + COL_SEAT + COL_COURSE;
        float regColRight = regColLeft + COL_REG;
        String curPrefix = extractRegPrefix(s.getRegNo());
        String curSuffix = extractRegSuffix(s.getRegNo());

        if (!isFirstOnPage && prevRegPrefix != null && prevRegPrefix.equalsIgnoreCase(curPrefix) && !curSuffix.isEmpty()) {
            // Same prefix as previous: display distinct suffix digits in 12.0pt Bold right-aligned
            drawRightAlignedText(cs, fontBold, 12f, curSuffix, regColRight - 3.0f, baseLine + 0.5f);
        } else {
            // New prefix or first on page: display full register number in 9.0pt Bold
            String reg = cleanText(s.getRegNo());
            drawText(cs, fontBold, 9f, reg, regColLeft + 3.0f, baseLine);
        }

        // 4. Name
        float nameColLeft = regColRight;
        String name = cleanText(s.getName());
        PDFont nameFont = s.isScribe() ? fontBold : fontRegular;
        String fitName = fitText(nameFont, 8.5f, name, COL_NAME - 6f);
        drawText(cs, nameFont, 8.5f, fitName, nameColLeft + 3.5f, baseLine);

        // 5. Remarks
        float remColLeft = nameColLeft + COL_NAME;
        String remarks = s.isScribe() ? "SCRIBE" : cleanText(s.getRemarks());
        if (!remarks.isEmpty()) {
            drawText(cs, fontBold, 8.5f, remarks, remColLeft + 3.5f, baseLine);
        }

        // 6. Sign: Blank cell
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

    private void drawLine(PDPageContentStream cs, float x1, float topDownY1, float x2, float topDownY2, float lineWidth) throws IOException {
        cs.setLineWidth(lineWidth);
        cs.moveTo(x1, toPdfY(topDownY1));
        cs.lineTo(x2, toPdfY(topDownY2));
        cs.stroke();
    }
}
