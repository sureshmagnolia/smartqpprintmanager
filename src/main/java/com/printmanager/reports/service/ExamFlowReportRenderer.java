package com.printmanager.reports.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.printmanager.reports.model.ExamFlowDataSet;
import com.printmanager.reports.model.ReportType;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Builds HTML representations for all ExamFlow reports, ensuring 100% visual
 * and structural parity with ExamFlow's print styles.
 */
public class ExamFlowReportRenderer {

    public static String renderReport(ReportType type, ExamFlowDataSet dataSet, String sessionKey) {
        if (type == null) return "<h3>Error: Report type is null</h3>";
        switch (type) {
            case QP_REQUIREMENT:
                return renderQPaperReport(dataSet, sessionKey);
            case QP_DISTRIBUTION:
                return renderQpDistributionReport(dataSet, sessionKey);
            case ROOM_WISE_SEATING:
                return renderRoomWiseSeatingReport(dataSet, sessionKey);
            case CANDIDATE_DAYWISE_SEATING:
                return renderCandidateDaywiseReport(dataSet, sessionKey);
            case ROOM_STICKERS:
                return renderRoomStickersReport(dataSet, sessionKey);
            case SCRIBE_PROFORMA:
                return renderScribeProformaReport(dataSet, sessionKey);
            case SCRIBE_ASSISTANCE:
                return renderScribeAssistanceReport(dataSet, sessionKey);
            case INVIGILATOR_REQUIREMENT:
                return renderInvigilatorRequirementReport(dataSet, sessionKey);
            case ROOM_ALLOTMENT_SUMMARY:
                return renderRoomAllotmentSummaryReport(dataSet, sessionKey);
            case SCRIBE_MASTER:
                return renderScribeMasterReport(dataSet);
            case ABSENTEE_STATEMENT:
                return renderAbsenteeStatementReport(dataSet, sessionKey);
            default:
                return "<h3>Report " + type.getDisplayName() + "</h3><p>Report generation not implemented.</p>";
        }
    }

    // ==========================================
    // 1. QUESTION PAPER REPORT (Simplex)
    // ==========================================
    public static String renderQPaperReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, Map<String, Integer>> streamCourseCounts = new TreeMap<>();
        Map<String, String> qpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());

        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        int grandTotal = 0;

        for (JsonNode room : rooms) {
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String stream = getStream(s);
                    String course = getCourse(s);
                    streamCourseCounts.putIfAbsent(stream, new TreeMap<>());
                    Map<String, Integer> cMap = streamCourseCounts.get(stream);
                    cMap.put(course, cMap.getOrDefault(course, 0) + 1);
                    grandTotal++;
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Question Paper Report", false));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Question Paper Requirement Abstract</h2>");
        html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
        html.append("</div>");

        if (streamCourseCounts.isEmpty()) {
            html.append("<div class='p-8 text-center text-red-600 font-bold'>No candidate allotment found for session: ").append(escapeHtml(sessionKey)).append("</div>");
        } else {
            for (Map.Entry<String, Map<String, Integer>> entry : streamCourseCounts.entrySet()) {
                String stream = entry.getKey();
                Map<String, Integer> courses = entry.getValue();
                int streamTotal = courses.values().stream().mapToInt(Integer::intValue).sum();

                html.append("<div style='margin-top:15px; margin-bottom:5px; font-weight:bold; font-size:11pt;'>Stream: ").append(escapeHtml(stream)).append("</div>");
                html.append("<table>");
                html.append("<thead><tr><th style='width:40px;'>Sl No</th><th>QP Code & Course Name</th><th style='width:90px;'>Required Count</th><th style='width:120px;'>Remarks</th></tr></thead>");
                html.append("<tbody>");

                int idx = 1;
                for (Map.Entry<String, Integer> cEntry : courses.entrySet()) {
                    String courseName = cEntry.getKey();
                    int count = cEntry.getValue();
                    String qpCode = lookupQpCode(qpCodes, courseName, stream);
                    String qpDisplay = qpCode.isEmpty() ? "" : "<strong>[" + escapeHtml(qpCode) + "]</strong> - ";

                    html.append("<tr>");
                    html.append("<td style='text-align:center;'>").append(idx++).append("</td>");
                    html.append("<td>").append(qpDisplay).append(escapeHtml(courseName)).append("</td>");
                    html.append("<td style='text-align:center; font-weight:bold;'>").append(count).append("</td>");
                    html.append("<td></td>");
                    html.append("</tr>");
                }
                html.append("<tr style='background:#f9f9f9; font-weight:bold;'>");
                html.append("<td colspan='2' style='text-align:right;'>Stream Subtotal:</td>");
                html.append("<td style='text-align:center;'>").append(streamTotal).append("</td>");
                html.append("<td></td>");
                html.append("</tr>");
                html.append("</tbody></table>");
            }

            html.append("<div style='margin-top:20px; font-weight:bold; font-size:12pt; text-align:right;'>Grand Total Question Papers: ").append(grandTotal).append("</div>");
            html.append(getSignaturesFooter("Question Paper Custodian", "Chief Superintendent"));
        }
        html.append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 2. QP DISTRIBUTION BY QP-CODE (Simplex)
    // ==========================================
    public static String renderQpDistributionReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> qpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());

        // Group by QP Code -> Course -> Map<RoomName, Count>
        Map<String, Map<String, Integer>> qpToRooms = new TreeMap<>();
        Map<String, String> qpToCourse = new HashMap<>();

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String course = getCourse(s);
                    String stream = getStream(s);
                    String qp = lookupQpCode(qpCodes, course, stream);
                    if (qp.isEmpty()) qp = "NO-QP: " + course;
                    qpToCourse.putIfAbsent(qp, course);

                    qpToRooms.putIfAbsent(qp, new TreeMap<>());
                    Map<String, Integer> rMap = qpToRooms.get(qp);
                    rMap.put(roomName, rMap.getOrDefault(roomName, 0) + 1);
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("QP Distribution by QP-Code", false));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Question Paper Distribution Checklist (Packing Desk)</h2>");
        html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
        html.append("</div>");

        if (qpToRooms.isEmpty()) {
            html.append("<div class='p-8 text-center text-red-600 font-bold'>No room allotment data available.</div>");
        } else {
            html.append("<table>");
            html.append("<thead><tr><th style='width:90px;'>QP Code</th><th>Course Title</th><th style='width:60px;'>Total</th><th>Room-wise Distribution Boxes [ Hall: Count ]</th></tr></thead>");
            html.append("<tbody>");

            for (Map.Entry<String, Map<String, Integer>> entry : qpToRooms.entrySet()) {
                String qp = entry.getKey();
                String course = qpToCourse.getOrDefault(qp, "");
                Map<String, Integer> rMap = entry.getValue();
                int totalForQp = rMap.values().stream().mapToInt(Integer::intValue).sum();

                StringBuilder boxes = new StringBuilder();
                boxes.append("<div style='display:flex; flex-wrap:wrap; gap:6px;'>");
                for (Map.Entry<String, Integer> rm : rMap.entrySet()) {
                    boxes.append("<span style='border:1px solid #444; padding:2px 6px; border-radius:3px; font-size:8.5pt; background:#fafafa;'>")
                         .append(escapeHtml(rm.getKey())).append(": <strong>").append(rm.getValue()).append("</strong> [ &nbsp; ]")
                         .append("</span>");
                }
                boxes.append("</div>");

                html.append("<tr>");
                html.append("<td style='font-weight:bold; font-family:monospace;'>").append(escapeHtml(qp)).append("</td>");
                html.append("<td>").append(escapeHtml(course)).append("</td>");
                html.append("<td style='text-align:center; font-weight:bold;'>").append(totalForQp).append("</td>");
                html.append("<td>").append(boxes).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
            html.append(getSignaturesFooter("Packing In-Charge", "Chief Superintendent"));
        }
        html.append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 3. ROOM-WISE SEATING (DUPLEX - SIGNING SHEET)
    // ==========================================
    public static String renderRoomWiseSeatingReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, String> qpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room-wise Seating & Attendance Register", true));

        if (rooms.isEmpty()) {
            html.append("<div class='print-page'><div class='header'><h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Room-wise Seating Report</h2><div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div></div>");
            html.append("<div style='text-align:center; padding:30px; font-weight:bold;'>No rooms allotted for this session.</div></div>");
        } else {
            for (int rIdx = 0; rIdx < rooms.size(); rIdx++) {
                JsonNode room = rooms.get(rIdx);
                String roomName = room.path("roomName").asText("Hall " + (rIdx + 1));
                JsonNode students = room.path("students");
                int studentCount = students.isArray() ? students.size() : 0;

                // Each hall forms its own duplex document leaf
                html.append("<div class='print-page print-page-room'>");
                html.append("<div class='header'>");
                html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
                html.append("<h2>Candidate Attendance & Seating Register</h2>");
                html.append("<div class='meta' style='display:flex; justify-content:space-between; margin-top:8px;'>");
                html.append("<span><strong>Room:</strong> ").append(escapeHtml(roomName)).append("</span>");
                html.append("<span><strong>Session:</strong> ").append(escapeHtml(sessionKey)).append("</span>");
                html.append("<span><strong>Allotted Candidates:</strong> ").append(studentCount).append("</span>");
                html.append("</div></div>");

                html.append("<table>");
                html.append("<thead><tr>");
                html.append("<th style='width:40px;'>Desk</th>");
                html.append("<th style='width:120px;'>Register No</th>");
                html.append("<th>Course & QP Code</th>");
                html.append("<th style='width:110px;'>Booklet Sl No</th>");
                html.append("<th style='width:130px;'>Candidate Signature</th>");
                html.append("</tr></thead><tbody>");

                if (students.isArray()) {
                    for (int sIdx = 0; sIdx < students.size(); sIdx++) {
                        JsonNode s = students.get(sIdx);
                        String regNo = getRegNo(s);
                        String course = getCourse(s);
                        String stream = getStream(s);
                        String seat = s.path("seat").asText(String.valueOf(sIdx + 1));
                        String qp = lookupQpCode(qpCodes, course, stream);
                        boolean isScribe = scribeAllot.containsKey(regNo);

                        String rowClass = isScribe ? "style='background-color:#fff3cd; font-weight:bold;'" : "";
                        String scribeBadge = isScribe ? " <span style='font-size:7.5pt; color:#d97706;'>[SCRIBE ALLOTTED]</span>" : "";

                        html.append("<tr ").append(rowClass).append(">");
                        html.append("<td style='text-align:center;'>").append(escapeHtml(seat)).append("</td>");
                        html.append("<td style='font-weight:bold; font-family:monospace;'>").append(escapeHtml(regNo)).append(scribeBadge).append("</td>");
                        html.append("<td>").append(qp.isEmpty() ? "" : "<strong>[" + escapeHtml(qp) + "]</strong> ").append(escapeHtml(course)).append("</td>");
                        html.append("<td></td>");
                        html.append("<td></td>");
                        html.append("</tr>");
                    }
                }

                html.append("</tbody></table>");

                // Statutory Footer on every hall signing leaf (strictly matching examflow / university statutes)
                html.append("<div style='margin-top:20px; border:1px solid #000; padding:10px; font-size:9pt;'>");
                html.append("<strong>Absentee Candidates (Register Numbers):</strong>");
                html.append("<div style='height:30px; border-bottom:1px solid #888; margin-bottom:10px;'></div>");
                html.append("<div style='font-style:italic; margin-bottom:5px;'>Certified that all candidate entries and booklet counts have been personally verified.</div>");
                html.append("<div style='display:flex; justify-content:space-between; margin-top:10px;'>");
                html.append("<span><strong>Invigilator Remarks:</strong> [ ] Nil &nbsp; [ ] Other: ____________________</span>");
                html.append("<span><strong>Invigilator Signature:</strong> ____________________</span>");
                html.append("</div></div>");

                html.append("</div>"); // End of hall page
            }
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 4. SEATING DETAILS FOR CANDIDATES (NOTICE BOARD - SIMPLEX)
    // ==========================================
    public static String renderCandidateDaywiseReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());

        // Flatten all students into an ordered list: RegNo -> RoomName
        List<Map.Entry<String, String>> studentRoomList = new ArrayList<>();
        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    studentRoomList.add(new AbstractMap.SimpleEntry<>(getRegNo(s), roomName));
                }
            }
        }
        // Sort alphabetically/numerically by Register Number
        studentRoomList.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Seating Arrangement for Candidates (Notice Board)", false));

        int itemsPerPage = 175; // 35 rows x 5 columns
        int totalPages = Math.max(1, (int) Math.ceil((double) studentRoomList.size() / itemsPerPage));

        for (int p = 0; p < totalPages; p++) {
            int start = p * itemsPerPage;
            int end = Math.min(start + itemsPerPage, studentRoomList.size());
            List<Map.Entry<String, String>> pageItems = studentRoomList.subList(start, end);

            html.append("<div class='print-page print-page-daywise'>");
            html.append("<div class='header' style='margin-bottom:8px;'>");
            html.append("<h1 style='font-size:14pt;'>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2 style='font-size:12pt; margin:2px 0;'>SEATING ARRANGEMENT FOR CANDIDATES</h2>");
            html.append("<div class='meta' style='font-size:9pt;'>Session: <strong>").append(escapeHtml(sessionKey))
                .append("</strong> | Page ").append(p + 1).append(" of ").append(totalPages).append("</div>");
            html.append("</div>");

            html.append("<div style='display:flex; gap:6px;'>");
            int colSize = 35;
            for (int col = 0; col < 5; col++) {
                int cStart = col * colSize;
                int cEnd = Math.min(cStart + colSize, pageItems.size());
                html.append("<div style='flex:1;'>");
                html.append("<table class='daywise-report-table'>");
                html.append("<thead><tr><th style='width:65%;'>Register No</th><th style='width:35%;'>Hall</th></tr></thead><tbody>");

                if (cStart < pageItems.size()) {
                    for (int i = cStart; i < cEnd; i++) {
                        Map.Entry<String, String> e = pageItems.get(i);
                        html.append("<tr>");
                        html.append("<td style='font-family:monospace; font-weight:bold;'>").append(escapeHtml(e.getKey())).append("</td>");
                        html.append("<td style='text-align:center;'>").append(escapeHtml(e.getValue())).append("</td>");
                        html.append("</tr>");
                    }
                }
                html.append("</tbody></table></div>");
            }
            html.append("</div>");
            html.append("</div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 5. ROOM STICKERS (2 PER PAGE - SIMPLEX)
    // ==========================================
    public static String renderRoomStickersReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> configs = dataSet.getRoomConfigs();

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room Stickers (Door Slips)", false));

        // 2 stickers per page
        for (int i = 0; i < rooms.size(); i += 2) {
            html.append("<div class='print-page print-page-sticker'>");

            for (int j = 0; j < 2; j++) {
                if (i + j < rooms.size()) {
                    JsonNode room = rooms.get(i + j);
                    String roomName = room.path("roomName").asText("Hall " + (i + j + 1));
                    JsonNode config = configs.get(roomName);
                    String location = (config != null && config.has("location")) ? config.get("location").asText() : "";
                    JsonNode students = room.path("students");
                    int count = students.isArray() ? students.size() : 0;

                    String firstReg = "";
                    String lastReg = "";
                    Set<String> courses = new TreeSet<>();
                    if (students.isArray() && students.size() > 0) {
                        firstReg = getRegNo(students.get(0));
                        lastReg = getRegNo(students.get(students.size() - 1));
                        for (JsonNode s : students) {
                            courses.add(getCourse(s));
                        }
                    }

                    html.append("<div class='exam-sticker'>");
                    html.append("<div style='text-align:center; border-bottom:2px solid #000; padding-bottom:6px;'>");
                    html.append("<div style='font-size:12pt; font-weight:bold; text-transform:uppercase;'>").append(escapeHtml(collegeName)).append("</div>");
                    html.append("<div style='font-size:16pt; font-weight:900; margin:4px 0;'>ROOM: ").append(escapeHtml(roomName)).append("</div>");
                    if (!location.isEmpty()) {
                        html.append("<div style='font-size:9.5pt; color:#444;'>Location: ").append(escapeHtml(location)).append("</div>");
                    }
                    html.append("</div>");

                    html.append("<div style='margin-top:10px; font-size:11pt;'>");
                    html.append("<div><strong>Date & Session:</strong> ").append(escapeHtml(sessionKey)).append("</div>");
                    html.append("<div><strong>Total Candidates:</strong> ").append(count).append("</div>");
                    if (!firstReg.isEmpty()) {
                        html.append("<div style='margin-top:6px; font-family:monospace; font-size:11pt;'><strong>Range:</strong> ")
                            .append(escapeHtml(firstReg)).append(" &nbsp;TO&nbsp; ").append(escapeHtml(lastReg)).append("</div>");
                    }
                    html.append("<div style='margin-top:6px; font-size:9.5pt;'><strong>Courses:</strong> ")
                        .append(escapeHtml(String.join(", ", courses))).append("</div>");
                    html.append("</div></div>");
                }
            }
            html.append("</div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 6. SCRIBE PROFORMA (ONE PAGE PER SCRIBE - SIMPLEX)
    // ==========================================
    public static String renderScribeProformaReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> scribes = dataSet.getScribes();

        Map<String, JsonNode> scribeMap = new HashMap<>();
        for (JsonNode sc : scribes) {
            String r = sc.path("regNo").asText("").trim();
            if (!r.isEmpty()) scribeMap.put(r, sc);
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Proforma", false));

        if (scribeAllot.isEmpty()) {
            html.append("<div class='print-page'><div class='header'><h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Scribe Proforma Declaration</h2></div><div style='text-align:center; padding:30px;'>No scribe assistance allocated for session: ")
                .append(escapeHtml(sessionKey)).append("</div></div>");
        } else {
            for (Map.Entry<String, String> entry : scribeAllot.entrySet()) {
                String regNo = entry.getKey();
                String hall = entry.getValue();
                JsonNode scData = scribeMap.get(regNo);
                String scribeName = (scData != null && scData.has("name")) ? scData.get("name").asText() : "____________________";
                String qualification = (scData != null && scData.has("qualification")) ? scData.get("qualification").asText() : "____________________";

                html.append("<div class='print-page'>");
                html.append("<div class='header'>");
                html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
                html.append("<h2>PROFORMA FOR APPOINTMENT OF SCRIBE / EXTRA TIME</h2>");
                html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
                html.append("</div>");

                html.append("<div style='margin-top:20px; line-height:1.8; font-size:10.5pt;'>");
                html.append("<table style='width:100%; border:none;'>");
                html.append("<tr><td style='border:none; width:50%;'><strong>Candidate Register Number:</strong> ").append(escapeHtml(regNo)).append("</td>");
                html.append("<td style='border:none;'><strong>Allotted Examination Hall:</strong> ").append(escapeHtml(hall)).append("</td></tr>");
                html.append("<tr><td style='border:none;'><strong>Scribe Name:</strong> ").append(escapeHtml(scribeName)).append("</td>");
                html.append("<td style='border:none;'><strong>Scribe Educational Qualification:</strong> ").append(escapeHtml(qualification)).append("</td></tr>");
                html.append("<tr><td style='border:none;'><strong>Compensatory Time Granted:</strong> 20 min / hour</td>");
                html.append("<td style='border:none;'><strong>Disability Certificate Verified:</strong> YES [ &nbsp; ]</td></tr>");
                html.append("</table>");

                html.append("<div style='margin-top:30px; border:1px solid #666; padding:15px; font-size:9.5pt; text-align:justify;'>");
                html.append("<strong>DECLARATION & UNDERTAKING:</strong><br/>");
                html.append("I hereby declare that the scribe appointed possesses lower educational qualification than the candidate appearing for the examination. " +
                            "I understand that any breach of examination confidentiality or university guidelines will result in disciplinary action.");
                html.append("</div>");

                html.append("<div style='margin-top:80px; display:flex; justify-content:space-between; text-align:center;'>");
                html.append("<div style='width:200px; border-top:1px solid #000; padding-top:5px;'>Signature of Candidate</div>");
                html.append("<div style='width:200px; border-top:1px solid #000; padding-top:5px;'>Signature of Scribe</div>");
                html.append("<div style='width:200px; border-top:1px solid #000; padding-top:5px;'>Chief Superintendent</div>");
                html.append("</div>");

                html.append("</div></div>");
            }
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // 7. SCRIBE ASSISTANCE REPORT (SIMPLEX)
    // ==========================================
    public static String renderScribeAssistanceReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> scribes = dataSet.getScribes();

        Map<String, JsonNode> scMap = new HashMap<>();
        for (JsonNode s : scribes) {
            scMap.put(s.path("regNo").asText("").trim(), s);
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Assistance Summary", false));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Scribe Assistance Duty Roster</h2>");
        html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
        html.append("</div>");

        if (scribeAllot.isEmpty()) {
            html.append("<div style='padding:40px; text-align:center;'>No scribes allotted for this session.</div>");
        } else {
            html.append("<table>");
            html.append("<thead><tr><th style='width:40px;'>Sl No</th><th>Candidate Reg No</th><th>Hall</th><th>Scribe Name</th><th>Qualification</th><th>Extra Time</th><th>Reliever Signature</th></tr></thead><tbody>");

            int sl = 1;
            for (Map.Entry<String, String> e : scribeAllot.entrySet()) {
                String reg = e.getKey();
                String hall = e.getValue();
                JsonNode sc = scMap.get(reg);
                String name = sc != null && sc.has("name") ? sc.get("name").asText() : "-";
                String qual = sc != null && sc.has("qualification") ? sc.get("qualification").asText() : "-";

                html.append("<tr>");
                html.append("<td style='text-align:center;'>").append(sl++).append("</td>");
                html.append("<td style='font-family:monospace; font-weight:bold;'>").append(escapeHtml(reg)).append("</td>");
                html.append("<td style='text-align:center; font-weight:bold;'>").append(escapeHtml(hall)).append("</td>");
                html.append("<td>").append(escapeHtml(name)).append("</td>");
                html.append("<td>").append(escapeHtml(qual)).append("</td>");
                html.append("<td style='text-align:center;'>+20 min/hr</td>");
                html.append("<td></td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
            html.append(getSignaturesFooter("Scribe Coordinator", "Chief Superintendent"));
        }
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==========================================
    // 8. INVIGILATOR REQUIREMENT SUMMARY (SIMPLEX)
    // ==========================================
    public static String renderInvigilatorRequirementReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, String> invigMap = dataSet.getInvigilatorMappings().getOrDefault(sessionKey, Collections.emptyMap());

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Invigilator Requirement & Duty Summary", false));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Invigilator Duty Assignment & Handover Summary</h2>");
        html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
        html.append("</div>");

        html.append("<table>");
        html.append("<thead><tr><th style='width:40px;'>Sl No</th><th>Hall Name</th><th>Students</th><th>Assigned Invigilator</th><th style='width:120px;'>Packet Receipt</th><th style='width:120px;'>Script Return</th></tr></thead><tbody>");

        int sl = 1;
        int totalStudents = 0;
        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            int count = students.isArray() ? students.size() : 0;
            totalStudents += count;
            String invig = invigMap.getOrDefault(roomName, "____________________");

            html.append("<tr>");
            html.append("<td style='text-align:center;'>").append(sl++).append("</td>");
            html.append("<td style='font-weight:bold;'>").append(escapeHtml(roomName)).append("</td>");
            html.append("<td style='text-align:center;'>").append(count).append("</td>");
            html.append("<td style='font-weight:bold;'>").append(escapeHtml(invig)).append("</td>");
            html.append("<td></td>");
            html.append("<td></td>");
            html.append("</tr>");
        }
        html.append("<tr style='background:#f5f5f5; font-weight:bold;'><td colspan='2' style='text-align:right;'>Total:</td><td style='text-align:center;'>")
            .append(totalStudents).append("</td><td colspan='3'></td></tr>");
        html.append("</tbody></table>");

        html.append(getSignaturesFooter("Invigilation In-Charge", "Chief Superintendent"));
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==========================================
    // 9. ROOM ALLOTMENT SUMMARY (SIMPLEX)
    // ==========================================
    public static String renderRoomAllotmentSummaryReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> configs = dataSet.getRoomConfigs();
        Map<String, String> invigMap = dataSet.getInvigilatorMappings().getOrDefault(sessionKey, Collections.emptyMap());

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room Allotment Summary", false));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Room Allotment Abstract (Executive)</h2>");
        html.append("<div class='meta'>Session: ").append(escapeHtml(sessionKey)).append("</div>");
        html.append("</div>");

        html.append("<table>");
        html.append("<thead><tr><th style='width:35px;'>Sl</th><th>Room Name</th><th style='width:60px;'>Cap</th><th style='width:60px;'>Allotted</th><th>Course Breakdown</th><th>Assigned Invigilator</th></tr></thead><tbody>");

        int sl = 1;
        int grandTotal = 0;
        int grandCap = 0;

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode config = configs.get(roomName);
            int capacity = (config != null && config.has("capacity")) ? config.get("capacity").asInt() : 30;
            grandCap += capacity;

            JsonNode students = room.path("students");
            int count = students.isArray() ? students.size() : 0;
            grandTotal += count;

            Map<String, Integer> courseCounts = new TreeMap<>();
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String c = getCourse(s);
                    courseCounts.put(c, courseCounts.getOrDefault(c, 0) + 1);
                }
            }

            StringBuilder cList = new StringBuilder();
            for (Map.Entry<String, Integer> ce : courseCounts.entrySet()) {
                if (cList.length() > 0) cList.append(", ");
                cList.append(ce.getKey()).append(" (").append(ce.getValue()).append(")");
            }

            String invig = invigMap.getOrDefault(roomName, "-");

            html.append("<tr>");
            html.append("<td style='text-align:center;'>").append(sl++).append("</td>");
            html.append("<td style='font-weight:bold;'>").append(escapeHtml(roomName)).append("</td>");
            html.append("<td style='text-align:center;'>").append(capacity).append("</td>");
            html.append("<td style='text-align:center; font-weight:bold;'>").append(count).append("</td>");
            html.append("<td style='font-size:8.5pt;'>").append(escapeHtml(cList.toString())).append("</td>");
            html.append("<td>").append(escapeHtml(invig)).append("</td>");
            html.append("</tr>");
        }

        html.append("<tr style='background:#eee; font-weight:bold;'>");
        html.append("<td colspan='2' style='text-align:right;'>Grand Total:</td>");
        html.append("<td style='text-align:center;'>").append(grandCap).append("</td>");
        html.append("<td style='text-align:center;'>").append(grandTotal).append("</td>");
        html.append("<td colspan='2'>Active Rooms: ").append(rooms.size()).append("</td>");
        html.append("</tr>");
        html.append("</tbody></table>");

        html.append(getSignaturesFooter("Observer / Flying Squad", "Chief Superintendent"));
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==========================================
    // 10. SCRIBE MASTER REPORT (DUPLEX - MULTI-SESSION)
    // ==========================================
    public static String renderScribeMasterReport(ExamFlowDataSet dataSet) {
        String collegeName = dataSet.getCollegeName();
        Map<String, Map<String, String>> allScribeAllot = dataSet.getScribeAllotments();
        List<JsonNode> scribes = dataSet.getScribes();

        Map<String, JsonNode> scMap = new HashMap<>();
        for (JsonNode s : scribes) {
            scMap.put(s.path("regNo").asText("").trim(), s);
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Assistance Master Ledger", true));
        html.append("<div class='print-page'>");
        html.append("<div class='header'>");
        html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2>Scribe Assistance Master Audit Register</h2>");
        html.append("<div class='meta'>Cumulative Multi-Session Audit Ledger</div>");
        html.append("</div>");

        html.append("<table>");
        html.append("<thead><tr><th style='width:40px;'>Sl No</th><th>Session Date & Time</th><th>Candidate Reg No</th><th>Hall</th><th>Scribe Name</th><th>Remuneration</th><th>Scribe Signature</th></tr></thead><tbody>");

        int sl = 1;
        List<String> sortedSessions = new ArrayList<>(allScribeAllot.keySet());
        sortedSessions.sort(ActiveSessionResolver::compareSessions);

        for (String sessionKey : sortedSessions) {
            Map<String, String> regRoomMap = allScribeAllot.get(sessionKey);
            for (Map.Entry<String, String> e : regRoomMap.entrySet()) {
                String reg = e.getKey();
                String hall = e.getValue();
                JsonNode sc = scMap.get(reg);
                String name = sc != null && sc.has("name") ? sc.get("name").asText() : "-";

                html.append("<tr>");
                html.append("<td style='text-align:center;'>").append(sl++).append("</td>");
                html.append("<td>").append(escapeHtml(sessionKey)).append("</td>");
                html.append("<td style='font-family:monospace; font-weight:bold;'>").append(escapeHtml(reg)).append("</td>");
                html.append("<td style='text-align:center;'>").append(escapeHtml(hall)).append("</td>");
                html.append("<td>").append(escapeHtml(name)).append("</td>");
                html.append("<td style='text-align:center;'>Rs. 250</td>");
                html.append("<td></td>");
                html.append("</tr>");
            }
        }

        if (sl == 1) {
            html.append("<tr><td colspan='7' style='text-align:center; padding:20px;'>No scribe assistance entries recorded across sessions.</td></tr>");
        }

        html.append("</tbody></table>");
        html.append(getSignaturesFooter("Accounts Superintendent", "Chief Superintendent"));
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==========================================
    // 11. ABSENTEE STATEMENT PROFORMA (SIMPLEX)
    // ==========================================
    public static String renderAbsenteeStatementReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        List<String> recordedAbsentees = dataSet.getAbsenteeList().getOrDefault(sessionKey, Collections.emptyList());
        Set<String> absSet = new HashSet<>(recordedAbsentees);

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Absentee Statement Proforma", false));

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            int total = students.isArray() ? students.size() : 0;

            List<String> roomAbs = new ArrayList<>();
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String reg = getRegNo(s);
                    if (absSet.contains(reg)) {
                        roomAbs.add(reg);
                    }
                }
            }

            html.append("<div class='print-page'>");
            html.append("<div class='header'>");
            html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>UNIVERSITY VALUATION CAMP - ABSENTEE STATEMENT</h2>");
            html.append("<div class='meta' style='display:flex; justify-content:space-between; margin-top:8px;'>");
            html.append("<span><strong>Room:</strong> ").append(escapeHtml(roomName)).append("</span>");
            html.append("<span><strong>Session:</strong> ").append(escapeHtml(sessionKey)).append("</span>");
            html.append("<span><strong>Total Registered:</strong> ").append(total).append("</span>");
            html.append("</div></div>");

            html.append("<div style='margin-top:20px; font-size:10.5pt;'>");
            html.append("<table style='width:100%; margin-bottom:20px;'>");
            html.append("<tr><th>Total Registered</th><th>Total Present</th><th>Total Absent</th></tr>");
            html.append("<tr><td style='text-align:center; font-weight:bold;'>").append(total).append("</td>");
            html.append("<td style='text-align:center; font-weight:bold;'>").append(total - roomAbs.size()).append("</td>");
            html.append("<td style='text-align:center; font-weight:bold; color:red;'>").append(roomAbs.size()).append("</td></tr>");
            html.append("</table>");

            html.append("<strong>List of Absentee Register Numbers:</strong>");
            html.append("<div style='min-height:120px; border:1px solid #000; padding:10px; margin-top:5px; font-family:monospace; font-size:11pt;'>");
            if (roomAbs.isEmpty()) {
                html.append("NIL (All candidates present or absentees not yet marked)");
            } else {
                html.append(String.join(", ", roomAbs));
            }
            html.append("</div>");

            html.append("<div style='margin-top:40px; display:flex; justify-content:space-between; text-align:center;'>");
            html.append("<div style='width:220px; border-top:1px solid #000; padding-top:5px;'>Assistant Superintendent (Invigilator)</div>");
            html.append("<div style='width:220px; border-top:1px solid #000; padding-top:5px;'>Chief Superintendent</div>");
            html.append("</div>");

            html.append("</div></div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    // ==========================================
    // HELPERS & PRINT STYLES
    // ==========================================
    private static String getBaseHtmlHead(String title, boolean duplex) {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset='utf-8'/>\n<title>" + escapeHtml(title) + "</title>\n" +
               "<style>\n" +
               "@page { size: A4 portrait; margin: 10mm; }\n" +
               "body { font-family: Arial, Helvetica, sans-serif; margin: 0; padding: 0; background: white; color: black; font-size: 9.5pt; }\n" +
               ".print-page { width: 100%; box-sizing: border-box; page-break-after: always; padding: 5mm; }\n" +
               ".header { text-align: center; border-bottom: 2px solid #000; padding-bottom: 8px; margin-bottom: 12px; }\n" +
               ".header h1 { margin: 0; font-size: 14pt; text-transform: uppercase; font-weight: bold; }\n" +
               ".header h2 { margin: 3px 0; font-size: 11pt; font-weight: bold; color: #222; }\n" +
               ".header .meta { font-size: 9pt; color: #333; margin-top: 4px; }\n" +
               "table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 9pt; }\n" +
               "th { border: 1px solid #000; padding: 5px 6px; background-color: #f0f0f0; font-weight: bold; text-align: left; }\n" +
               "td { border: 1px solid #000; padding: 4px 6px; }\n" +
               ".daywise-report-table { border-collapse: collapse; width: 100%; table-layout: fixed; }\n" +
               ".daywise-report-table td, .daywise-report-table th { padding: 1px 3px; font-size: 7.5pt; border: 1px solid #000; overflow: hidden; white-space: nowrap; }\n" +
               ".daywise-report-table th { background: #eee; font-size: 7pt; text-align: center; }\n" +
               ".print-page-sticker { display: flex; flex-direction: column; justify-content: space-between; height: 260mm; box-sizing: border-box; page-break-after: always; }\n" +
               ".exam-sticker { border: 2px dashed #000; height: 125mm; padding: 12mm; box-sizing: border-box; }\n" +
               "@media print {\n" +
               "  body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n" +
               "  .print-page { page-break-after: always; }\n" +
               "}\n" +
               "</style>\n</head>\n<body>\n";
    }

    private static String getSignaturesFooter(String leftRole, String rightRole) {
        return "<div style='margin-top:50px; display:flex; justify-content:space-between; padding:0 30px;'>" +
               "<div style='text-align:center; min-width:200px; border-top:1px solid #000; padding-top:5px; font-weight:bold;'>" + escapeHtml(leftRole) + "</div>" +
               "<div style='text-align:center; min-width:200px; border-top:1px solid #000; padding-top:5px; font-weight:bold;'>" + escapeHtml(rightRole) + "</div>" +
               "</div>";
    }

    public static String getRegNo(JsonNode s) {
        if (s == null || s.isNull()) return "";
        for (String k : new String[]{"Register Number", "RegisterNo", "regNo", "Reg No", "RegNo", "register_number"}) {
            if (s.has(k)) {
                String val = s.get(k).asText("").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }

    public static String getCourse(JsonNode s) {
        if (s == null || s.isNull()) return "";
        for (String k : new String[]{"Course", "CourseName", "course", "paper", "Subject"}) {
            if (s.has(k)) {
                String val = s.get(k).asText("").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }

    public static String getStream(JsonNode s) {
        if (s == null || s.isNull()) return "Regular";
        for (String k : new String[]{"Stream", "stream"}) {
            if (s.has(k)) {
                String val = s.get(k).asText("").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "Regular";
    }

    public static String lookupQpCode(Map<String, String> qpMap, String course, String stream) {
        if (qpMap == null || qpMap.isEmpty() || course == null) return "";
        String cleanCourse = course.trim();
        String s = (stream != null && !stream.trim().isEmpty()) ? stream.trim() : "Regular";
        
        // Exact Base64 key match: Base64(course|stream)
        String rawKey = cleanCourse + "|" + s;
        String b64Key = Base64.getEncoder().encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
        if (qpMap.containsKey(b64Key)) {
            return qpMap.get(b64Key);
        }

        // Fallback: search values or keys containing course
        for (Map.Entry<String, String> e : qpMap.entrySet()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(e.getKey());
                String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                if (decodedStr.startsWith(cleanCourse)) {
                    return e.getValue();
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
