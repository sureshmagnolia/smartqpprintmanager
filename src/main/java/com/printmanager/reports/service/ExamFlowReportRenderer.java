package com.printmanager.reports.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.printmanager.reports.model.ExamFlowDataSet;
import com.printmanager.reports.model.ReportType;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Builds HTML representations for all ExamFlow reports, reproducing the exact
 * layout, HTML tags, class names, font sizes, booklet accounting blocks,
 * statutory duplex leaves, notice board rolls, stickers, and footers as coded in ExamFlow.
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

    // =========================================================================
    // 1. QUESTION PAPER REPORT (Simplex - matches r1_qpaper.js)
    // =========================================================================
    public static String renderQPaperReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());

        // Group by Stream -> Course -> Count
        Map<String, Map<String, Integer>> streamMap = new TreeMap<>((a, b) -> {
            if (a.equalsIgnoreCase(b)) return 0;
            if ("Regular".equalsIgnoreCase(a)) return -1;
            if ("Regular".equalsIgnoreCase(b)) return 1;
            return a.compareToIgnoreCase(b);
        });

        int grandTotal = 0;
        for (JsonNode room : rooms) {
            String roomStream = room.path("stream").asText("Regular");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String stream = getStream(s, roomStream);
                    String course = getCourse(s);
                    if (course.isEmpty()) course = "General";
                    Map<String, Integer> cMap = streamMap.computeIfAbsent(stream, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
                    cMap.put(course, cMap.getOrDefault(course, 0) + 1);
                    grandTotal++;
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Question Paper Summary", false));

        html.append("<div class='print-page'>");
        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:1.5rem;'>");
        html.append("<h1 style='font-size:16pt; font-weight:bold; margin-bottom:0.3rem; text-transform:uppercase;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2 style='font-size:14pt; font-weight:bold; margin:0.3rem 0;'>Question Paper Summary</h2>");
        html.append("<h3 style='font-size:12pt; font-weight:bold; margin-top:0.3rem;'>")
            .append(escapeHtml(sessionKey)).append("</h3>");
        html.append("</div>");

        if (streamMap.isEmpty()) {
            html.append("<div style='text-align:center; padding:30px; font-weight:bold; color:#b91c1c;'>No candidate allotment found for session: ")
                .append(escapeHtml(sessionKey)).append("</div>");
        } else {
            for (Map.Entry<String, Map<String, Integer>> streamEntry : streamMap.entrySet()) {
                String streamName = streamEntry.getKey();
                Map<String, Integer> courses = streamEntry.getValue();
                int totalInStream = courses.values().stream().mapToInt(Integer::intValue).sum();

                html.append("<h3 style='text-align:left; margin-top:1.5rem; margin-bottom:0.5rem; border-bottom:2px solid #000; display:inline-block; font-size:11pt;'>Stream: ")
                    .append(escapeHtml(streamName)).append("</h3>");

                html.append("<table class='q-paper-table print-table' style='margin-bottom:2rem;'>");
                html.append("<thead><tr>");
                html.append("<th class='sl-col' style='text-align:center; width:60px;'>Sl No</th>");
                html.append("<th class='course-col'>Course Name</th>");
                html.append("<th class='count-col' style='text-align:center; width:100px;'>Count</th>");
                html.append("</tr></thead><tbody>");

                int idx = 1;
                for (Map.Entry<String, Integer> cEntry : courses.entrySet()) {
                    String courseName = cEntry.getKey();
                    int count = cEntry.getValue();
                    String qp = lookupQpCode(sessionQpCodes, courseName, streamName);
                    String qpDisplay = qp.isEmpty() ? "" : "<b style='margin-right:5px;'>[" + escapeHtml(qp) + "]</b> ";

                    html.append("<tr>");
                    html.append("<td class='sl-col' style='text-align:center;'>").append(idx++).append("</td>");
                    html.append("<td class='course-col'>").append(qpDisplay).append(escapeHtml(courseName)).append("</td>");
                    html.append("<td class='count-col' style='text-align:center; font-weight:bold;'>").append(count).append("</td>");
                    html.append("</tr>");
                }

                html.append("</tbody><tfoot><tr>");
                html.append("<td colspan='2' style='text-align:right;'><strong>Total (").append(escapeHtml(streamName)).append(")</strong></td>");
                html.append("<td class='count-col' style='text-align:center;'><strong>").append(totalInStream).append("</strong></td>");
                html.append("</tr></tfoot></table>");
            }

            html.append("<div style='margin-top:20px; font-weight:bold; font-size:12pt; text-align:right;'>Grand Total Question Papers: ")
                .append(grandTotal).append("</div>");
            html.append(getSignaturesFooter("Question Paper Custodian", "Chief Superintendent"));
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    // =========================================================================
    // 2. QP DISTRIBUTION REPORT (Simplex - matches r2_qp_dist.js)
    // =========================================================================
    public static String renderQpDistributionReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> roomConfigs = dataSet.getRoomConfigs();

        // Build room serial map (1-based index)
        Map<String, Integer> roomSerialMap = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            roomSerialMap.put(rooms.get(i).path("roomName").asText("Hall " + (i + 1)), i + 1);
        }

        // Structure: Stream -> PaperKey -> { courseName, stream, qpCode, total, Map<RoomName, Count> }
        class PaperEntry {
            String courseName;
            String stream;
            String qpCode;
            int total = 0;
            Map<String, Integer> roomCounts = new TreeMap<>();
        }

        Map<String, PaperEntry> paperMap = new LinkedHashMap<>();

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            String defaultStream = room.path("stream").asText("Regular");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String course = getCourse(s);
                    String stream = getStream(s, defaultStream);
                    String paperKey = course + "|" + stream;

                    PaperEntry pe = paperMap.computeIfAbsent(paperKey, k -> {
                        PaperEntry p = new PaperEntry();
                        p.courseName = course;
                        p.stream = stream;
                        String q = lookupQpCode(sessionQpCodes, course, stream);
                        p.qpCode = q.isEmpty() ? "N/A" : q;
                        return p;
                    });
                    pe.total++;
                    pe.roomCounts.put(roomName, pe.roomCounts.getOrDefault(roomName, 0) + 1);
                }
            }
        }

        // Separate Regular Stream from Other Streams
        List<PaperEntry> regularPapers = new ArrayList<>();
        List<PaperEntry> otherPapers = new ArrayList<>();

        for (PaperEntry pe : paperMap.values()) {
            if ("Regular".equalsIgnoreCase(pe.stream)) {
                regularPapers.add(pe);
            } else {
                otherPapers.add(pe);
            }
        }

        Comparator<PaperEntry> paperComp = Comparator.comparing((PaperEntry p) -> p.courseName, String.CASE_INSENSITIVE_ORDER);
        regularPapers.sort(paperComp);
        otherPapers.sort(paperComp);

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("QP Distribution Summary", false));

        html.append("<div class='print-page' style='padding:5mm !important;'>");
        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:12px; border-bottom:2px solid #000; padding-bottom:4px;'>");
        html.append("<h1 style='font-size:14pt; font-weight:bold; text-transform:uppercase; margin:0;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2 style='font-size:12pt; font-weight:600; margin:2px 0;'>QP Distribution Summary</h2>");
        html.append("<h3 style='font-size:10pt; margin:2px 0;'>").append(escapeHtml(sessionKey)).append("</h3>");
        html.append("</div>");

        if (paperMap.isEmpty()) {
            html.append("<div style='text-align:center; padding:30px; font-weight:bold; color:#b91c1c;'>No allotment data available.</div>");
        } else {
            renderQpDistSection(html, regularPapers, "Regular Stream", "#fff", "border: 1px solid #000;", roomSerialMap, roomConfigs);
            renderQpDistSection(html, otherPapers, "Other Streams", "#fffbeb", "border: 2px dashed #000;", roomSerialMap, roomConfigs);
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private static void renderQpDistSection(StringBuilder html, List<?> papers, String title,
                                           String bgClass, String borderStyle,
                                           Map<String, Integer> roomSerialMap,
                                           Map<String, JsonNode> roomConfigs) {
        if (papers.isEmpty()) return;

        html.append("<div style='font-weight:bold; font-size:10pt; text-transform:uppercase; border-bottom:2px solid #000; margin-top:14px; margin-bottom:6px; padding-bottom:3px;'>")
            .append(escapeHtml(title)).append("</div>");

        for (Object obj : papers) {
            // Using reflection or inner structure
            try {
                java.lang.reflect.Field fCourse = obj.getClass().getDeclaredField("courseName");
                java.lang.reflect.Field fStream = obj.getClass().getDeclaredField("stream");
                java.lang.reflect.Field fQp = obj.getClass().getDeclaredField("qpCode");
                java.lang.reflect.Field fTotal = obj.getClass().getDeclaredField("total");
                java.lang.reflect.Field fRooms = obj.getClass().getDeclaredField("roomCounts");

                String courseName = (String) fCourse.get(obj);
                String stream = (String) fStream.get(obj);
                String qpCode = (String) fQp.get(obj);
                int total = fTotal.getInt(obj);
                @SuppressWarnings("unchecked")
                Map<String, Integer> roomCounts = (Map<String, Integer>) fRooms.get(obj);

                String qpBadge = !"N/A".equals(qpCode)
                        ? "<span style='background:#fff; color:#000; padding:1px 5px; border-radius:3px; font-size:8pt; font-weight:bold; border:1px solid #000;'>" + escapeHtml(qpCode) + "</span>"
                        : "<span style='color:#999; font-size:7.5pt; font-style:italic;'>(QP Missing)</span>";

                String streamBadge = "<span style='padding:1px 4px; border-radius:3px; border:1px solid #ccc; font-size:7pt; font-weight:bold; text-transform:uppercase; background:" +
                        ("Regular Stream".equals(title) ? "#eff6ff; color:#1e40af;" : "#faf5ff; color:#6b21a8;") + "'>" + escapeHtml(stream) + "</span>";

                html.append("<div style='margin-top:8px; padding:4px 6px; page-break-inside:avoid; border-radius:4px; ")
                    .append(borderStyle).append(" background:").append(bgClass).append(";'>");

                // Header of Paper Card
                html.append("<div style='display:flex; justify-content:space-between; align-items:flex-start; border-bottom:1px dotted #888; padding-bottom:3px; margin-bottom:4px;'>");
                html.append("<div style='width:90%;'>");
                html.append("<div style='font-weight:bold; font-size:9pt; line-height:1.2; color:#111; margin-bottom:2px;'>").append(escapeHtml(courseName)).append("</div>");
                html.append("<div style='display:flex; align-items:center; gap:6px;'>").append(streamBadge)
                    .append("<span style='font-size:7.5pt; font-weight:600; color:#444;'>QP: ").append(qpBadge).append("</span></div>");
                html.append("</div>");
                html.append("<div style='width:10%; text-align:right;'>");
                html.append("<span style='font-size:9pt; font-weight:900; border:1px solid #000; padding:1px 6px; background:#fff; display:inline-block;'>").append(total).append("</span>");
                html.append("</div>");
                html.append("</div>");

                // Room Grid (3 columns)
                html.append("<div style='display:grid; grid-template-columns: repeat(3, 1fr); gap:6px;'>");

                List<String> sortedRooms = new ArrayList<>(roomCounts.keySet());
                sortedRooms.sort((a, b) -> {
                    int sA = roomSerialMap.getOrDefault(a, 999);
                    int sB = roomSerialMap.getOrDefault(b, 999);
                    return Integer.compare(sA, sB);
                });

                for (String roomName : sortedRooms) {
                    int count = roomCounts.get(roomName);
                    int serialNo = roomSerialMap.getOrDefault(roomName, 0);
                    JsonNode rCfg = roomConfigs.get(roomName);
                    String loc = (rCfg != null && rCfg.has("location")) ? rCfg.get("location").asText("").trim() : "";
                    if (!loc.isEmpty()) {
                        String[] words = loc.split("\\s+");
                        if (words.length > 2) {
                            loc = words[0] + " " + words[1] + "..";
                        }
                    }
                    String displayLoc = loc.isEmpty() ? "" : "(" + loc + ")";

                    html.append("<div style='border:1px solid #888; border-radius:3px; padding:2px 5px; background:#fff; height:32px; display:flex; align-items:center; justify-content:space-between; box-shadow:0 1px 2px rgba(0,0,0,0.05);'>");
                    html.append("<div style='display:flex; align-items:baseline; overflow:hidden; width:100%; white-space:nowrap;'>");
                    html.append("<span style='font-size:12pt; font-weight:900; color:#000; margin-right:2px;'>").append(count).append("</span>");
                    html.append("<span style='font-size:7pt; font-weight:bold; color:#666; margin-right:4px;'>Nos</span>");
                    html.append("<span style='color:#ccc; margin-right:4px; font-size:8pt;'>|</span>");
                    html.append("<div style='overflow:hidden; text-overflow:ellipsis;'>");
                    html.append("<span style='font-size:8.5pt; font-weight:900; color:#000; margin-right:3px;'>Room #").append(serialNo).append("</span>");
                    html.append("<span style='font-size:7pt; font-weight:bold; color:#666;'>").append(escapeHtml(displayLoc)).append("</span>");
                    html.append("</div></div>");
                    html.append("<span style='width:13px; height:13px; border:2px solid #000; background:#fff; border-radius:2px; flex-shrink:0; margin-left:4px;'></span>");
                    html.append("</div>");
                }

                html.append("</div></div>"); // End paper card
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 3. ROOM-WISE SEATING (DUPLEX - SIGNING SHEET - matches report3_examflow.js)
    // =========================================================================
    public static String renderRoomWiseSeatingReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        Map<String, JsonNode> roomConfigs = dataSet.getRoomConfigs();
        Map<String, String> invigMap = dataSet.getInvigilatorMappings().getOrDefault(sessionKey, Collections.emptyMap());
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());
        Set<String> globalScribes = new HashSet<>();
        for (JsonNode sc : dataSet.getScribes()) {
            String r = sc.path("regNo").asText("").trim();
            if (!r.isEmpty()) globalScribes.add(r);
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room-wise Seating & Attendance Register", true));

        if (rooms.isEmpty()) {
            html.append("<div class='print-page'><div class='print-header-group text-center'>");
            html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Room-wise Seating Report</h2><h3>").append(escapeHtml(sessionKey)).append("</h3></div>");
            html.append("<div style='text-align:center; padding:30px; font-weight:bold;'>No rooms allotted for this session.</div></div>");
        } else {
            for (int rIdx = 0; rIdx < rooms.size(); rIdx++) {
                JsonNode room = rooms.get(rIdx);
                int serialNo = rIdx + 1;
                String roomName = room.path("roomName").asText("Hall " + serialNo);
                String pageStream = room.path("stream").asText("Regular");
                JsonNode config = roomConfigs.get(roomName);
                String location = (config != null && config.has("location")) ? config.get("location").asText("").trim() : "";
                String locationHtml = !location.isEmpty()
                        ? "<div class='report-location-header' style='font-size:10pt; font-weight:bold; font-style:italic; text-align:center; border-bottom:1px solid #ccc; padding-bottom:3px; margin-bottom:4px;'>Location: " + escapeHtml(location) + "</div>"
                        : "";

                String examName = dataSet.getExamName(sessionKey, pageStream);
                String examNameHtml = !examName.isEmpty()
                        ? "<div style='font-size:12pt; font-weight:bold; margin:2px 0; text-align:center; text-transform:uppercase;'>" + escapeHtml(examName) + "</div>"
                        : "";

                String assignedInvigilator = invigMap.getOrDefault(roomName, "Name &amp; Signature of Invigilator");

                // Parse students into model rows
                List<JsonNode> studentList = new ArrayList<>();
                JsonNode studentsNode = room.path("students");
                if (studentsNode.isArray()) {
                    for (JsonNode s : studentsNode) studentList.add(s);
                }

                // Sort students by seatNumber (1..N)
                studentList.sort(Comparator.comparingInt(s -> s.path("seat").asInt(999)));

                // Chunk into Page 1 (first 20) and Page 2 (remaining)
                int p1Limit = Math.min(20, studentList.size());
                List<JsonNode> page1Students = studentList.subList(0, p1Limit);
                List<JsonNode> page2Students = studentList.subList(p1Limit, studentList.size());

                // Calculate Course Counts for this Room Leaf (Stats)
                Map<String, int[]> courseStats = new LinkedHashMap<>(); // Key -> [total, scribes]
                Set<String> uniqueQps = new LinkedHashSet<>();
                int totalNormalStudents = 0;

                for (JsonNode s : studentList) {
                    String reg = getRegNo(s);
                    String course = getCourse(s);
                    String stream = getStream(s, pageStream);
                    boolean isScribe = globalScribes.contains(reg) || scribeAllot.containsKey(reg);

                    String comboKey = course + "|" + stream;
                    int[] counts = courseStats.computeIfAbsent(comboKey, k -> new int[2]);
                    counts[0]++; // total
                    if (isScribe) counts[1]++; // scribe
                    else totalNormalStudents++;

                    String qp = lookupQpCode(sessionQpCodes, course, stream);
                    if (!qp.isEmpty()) uniqueQps.add(qp);
                }

                // -------------------------------------------------------------
                // HALL LEAF - PAGE 1 (Front Side of Duplex Leaf)
                // -------------------------------------------------------------
                html.append("<div class='print-page print-page-room'>");

                // Page 1 Header
                html.append("<div class='print-header-group' style='position:relative; text-align:center; margin-bottom:5px;'>");
                html.append("<div style='position:absolute; top:0; right:0; font-weight:bold; font-size:11pt; border:1px solid #000; padding:2px 6px;'>")
                    .append(escapeHtml(pageStream)).append("</div>");
                html.append("<div style='position:absolute; top:0; left:0; font-weight:bold; font-size:12pt;'>Page 1</div>");
                html.append("<h1 style='margin:0 0 2px 0; text-transform:uppercase; font-size:15pt; font-weight:bold;'>").append(escapeHtml(collegeName)).append("</h1>");
                html.append(examNameHtml);
                html.append("<h2 style='margin:2px 0; font-size:13pt; font-weight:bold;'>Hall: ").append(serialNo)
                    .append(" &nbsp;|&nbsp; ").append(escapeHtml(sessionKey)).append("</h2>");
                html.append(locationHtml);
                html.append("</div>");

                // Table Page 1
                html.append(renderSeatingTable(page1Students, sessionQpCodes, globalScribes, scribeAllot, pageStream));

                html.append("<div style='text-align:right; font-size:8.5pt; font-weight:bold; margin-top:8px; color:#444;'>Continued on Page 2...</div>");
                html.append("</div>"); // End of Page 1

                // -------------------------------------------------------------
                // HALL LEAF - PAGE 2 (Back Side of Duplex Leaf)
                // -------------------------------------------------------------
                html.append("<div class='print-page print-page-room'>");

                // Page 2 Header
                html.append("<div class='print-header-group' style='margin-bottom:6px; border-bottom:1px dashed #ccc; padding-bottom:3px;'>");
                html.append("<div style='display:flex; justify-content:space-between; align-items:center; font-weight:bold; font-size:10pt; color:#444;'>");
                html.append("<span>Hall: ").append(serialNo).append(" (").append(escapeHtml(roomName)).append(")</span>");
                html.append("<span style='font-size:11pt; color:#000;'>").append(escapeHtml(examName)).append("</span>");
                html.append("<span>Page 2 - ").append(escapeHtml(pageStream)).append("</span>");
                html.append("</div></div>");

                // Table Page 2
                if (!page2Students.isEmpty()) {
                    html.append(renderSeatingTable(page2Students, sessionQpCodes, globalScribes, scribeAllot, pageStream));
                } else {
                    html.append("<div style='padding:12px; text-align:center; font-style:italic; border-bottom:1px solid #ccc; font-size:9pt;'>(End of Student List)</div>");
                }

                // Page 2 Invigilator & Accounting Footer
                html.append("<div class='invigilator-footer' style='margin-top:0.75rem; padding-top:0; page-break-inside:avoid; font-size:9pt;'>");

                // Attendance Summary Box (strictly matching ExamFlow V12.8.5 Page 2 & Page 4)
                html.append("<div class='attendance-summary-box' style='background-color:#f9fafb; border:1px solid #000; padding:5px 8px; margin:6px 0 8px 0; font-size:8.5pt; display:flex; justify-content:flex-start; align-items:center;'>");
                html.append("<span style='font-weight:bold; margin-right:4px;'>Attendance Summary:</span> ");
                html.append("<span>Students Assigned: <b>").append(totalNormalStudents).append("</b>;&nbsp;&nbsp;&nbsp;&nbsp;</span>");
                html.append("<span>Total Students Present: <span style='display:inline-block; border-bottom:1px solid #000; width:45px;'></span>;&nbsp;&nbsp;&nbsp;&nbsp;</span>");
                html.append("<span>Total Students Absent: <span style='display:inline-block; border-bottom:1px solid #000; width:45px;'></span></span>");
                html.append("</div>");

                // Course Summary Table
                html.append("<div style='margin-bottom:6px;'>");
                html.append("<div style='font-weight:bold; margin-bottom:2px; font-size:8.5pt;'>Course Summary:</div>");
                html.append("<table style='width:100%; border-collapse:collapse; font-size:8.5pt;'>");
                html.append("<thead><tr style='background-color:#f0f0f0;'>");
                html.append("<th style='border:1px solid #ccc; padding:2px 4px; text-align:left; width:16%;'>QP Code</th>");
                html.append("<th style='border:1px solid #ccc; padding:2px 4px; text-align:left; width:68%;'>Course Name</th>");
                html.append("<th style='border:1px solid #ccc; padding:2px 4px; text-align:center; width:16%;'>Count</th>");
                html.append("</tr></thead><tbody>");

                for (Map.Entry<String, int[]> ce : courseStats.entrySet()) {
                    String[] parts = ce.getKey().split("\\|");
                    String cName = parts[0];
                    String cStream = parts.length > 1 ? parts[1] : pageStream;
                    int totalCount = ce.getValue()[0];
                    int scribeCount = ce.getValue()[1];
                    int adjustedCount = totalCount - scribeCount;

                    String qp = lookupQpCode(sessionQpCodes, cName, cStream);
                    String qpDisplay = qp.isEmpty() ? "N/A" : qp;
                    String smart = getSmartCourseName(cName);
                    if (scribeCount > 0) {
                        smart += " <b>(" + scribeCount + " Scribes)</b>";
                    }

                    html.append("<tr>");
                    html.append("<td style='border:1px solid #ccc; padding:2px 4px; font-weight:bold;'>").append(escapeHtml(qpDisplay)).append("</td>");
                    html.append("<td style='border:1px solid #ccc; padding:2px 4px;'>").append(smart).append("</td>");
                    html.append("<td style='border:1px solid #ccc; padding:2px 4px; text-align:center; font-weight:bold;'>").append(adjustedCount).append("</td>");
                    html.append("</tr>");
                }

                html.append("<tr style='background-color:#f9fafb;'>");
                html.append("<td colspan='2' style='border:1px solid #ccc; padding:2px 4px; text-align:right; font-weight:bold;'>Total (Excl. Scribes):</td>");
                html.append("<td style='border:1px solid #ccc; padding:2px 4px; text-align:center; font-weight:bold;'>").append(totalNormalStudents).append("</td>");
                html.append("</tr></tbody></table></div>");

                // Booklet Accounting Box
                html.append("<div style='border:1px solid #000; padding:6px; margin-bottom:8px; font-size:8.5pt;'>");
                html.append("<div style='display:flex; justify-content:space-between; margin-bottom:6px; font-weight:bold;'>");
                html.append("<span>Booklets Received: __________</span>");
                html.append("<span>Used: __________</span>");
                html.append("<span>Balance Returned: __________</span>");
                html.append("</div>");

                html.append("<div style='border-top:1px dotted #999; padding-top:4px; margin-bottom:4px;'>");
                html.append("<strong>Written Booklets (QP Wise):</strong><br/>");
                html.append("<div style='margin-top:2px; line-height:1.5;'>");
                for (String qp : uniqueQps) {
                    html.append("<span style='margin-right:15px; white-space:nowrap;'>")
                        .append(escapeHtml(qp)).append(": <span style='border-bottom:1px solid #000; display:inline-block; width:35px;'></span></span> ");
                }
                html.append("</div></div>");

                html.append("<div style='border-top:1px dotted #999; padding-top:4px; text-align:right;'>");
                html.append("<strong>Written Booklets Total:</strong> __________");
                html.append("</div></div>");

                // Signature and Scribe Footnote
                boolean hasScribeInHall = courseStats.values().stream().anyMatch(c -> c[1] > 0);
                String scribeFootnote = hasScribeInHall ? "<div style='font-size:8.5pt; font-style:italic;'>* = Scribe Assistance</div>" : "";

                html.append("<div style='display:flex; justify-content:space-between; align-items:flex-end; margin-top:12px;'>");
                html.append("<div style='font-size:8.5pt;'>").append(scribeFootnote).append("</div>");
                html.append("<div style='text-align:center; width:220px;'>");
                html.append("<div style='border-top:1px solid #000; padding-top:4px; font-weight:bold; font-size:9pt;'>")
                    .append(escapeHtml(assignedInvigilator)).append("</div>");
                html.append("</div></div>");

                html.append("</div>"); // End invigilator-footer
                html.append("</div>"); // End of Page 2
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String renderSeatingTable(List<JsonNode> students, Map<String, String> qpMap,
                                             Set<String> globalScribes, Map<String, String> scribeAllot,
                                             String defaultStream) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table class='print-table' style='border-collapse:collapse; width:100%; font-size:8.5pt;'>");
        sb.append("<thead><tr>");
        sb.append("<th class='sl-col' style='padding:3px; width:45px; text-align:center;'>Seat</th>");
        sb.append("<th class='course-col' style='padding:3px;'>Course (QP Code)</th>");
        sb.append("<th class='reg-col' style='padding:3px; width:110px;'>Register Number</th>");
        sb.append("<th class='name-col' style='padding:3px;'>Name</th>");
        sb.append("<th class='remarks-col' style='padding:3px; width:75px;'>Remarks</th>");
        sb.append("<th class='signature-col' style='padding:3px; width:100px; text-align:center;'>Sign</th>");
        sb.append("</tr></thead><tbody>");

        String previousCourseKey = "";
        String previousRegNoPrefix = "";

        for (int i = 0; i < students.size(); i++) {
            JsonNode s = students.get(i);
            String regNo = getRegNo(s);
            String course = getCourse(s);
            String stream = getStream(s, defaultStream);
            String seatNumber = s.path("seat").asText(String.valueOf(i + 1));
            boolean isScribe = globalScribes.contains(regNo) || scribeAllot.containsKey(regNo);
            String asterisk = isScribe ? "*" : "";

            // Register number compression: if consecutive prefix matches, only show numeric part
            String displayRegNo = regNo;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-zA-Z\\-_]*)(\\d+)$").matcher(regNo);
            if (m.matches()) {
                String prefix = m.group(1);
                String number = m.group(2);
                if (prefix.equals(previousRegNoPrefix)) {
                    displayRegNo = number;
                }
                previousRegNoPrefix = prefix;
            } else {
                previousRegNoPrefix = "";
            }

            // Dynamic font size for Register Number
            String regFontSize = "12pt";
            if (displayRegNo.matches(".*[a-zA-Z].*")) {
                regFontSize = "9pt";
            } else if (displayRegNo.length() > 5) {
                regFontSize = "11pt";
            }

            // Course Cell formatting & Ditto (" ) suppression
            String qp = lookupQpCode(qpMap, course, stream);
            String currentCourseKey = qp + "|" + course;
            boolean isDitto = currentCourseKey.equals(previousCourseKey);
            previousCourseKey = currentCourseKey;

            String courseCellHtml;
            String smart = getSmartCourseName(course);
            if (isDitto) {
                courseCellHtml = "<div style='white-space:nowrap; overflow:hidden; text-overflow:ellipsis;'><span style='font-weight:bold; margin-right:6px;'>" + escapeHtml(qp) + "</span> \"</div>";
            } else {
                courseCellHtml = "<div style='white-space:nowrap; overflow:hidden; text-overflow:ellipsis;'><span style='font-weight:bold; margin-right:6px;'>" + escapeHtml(qp) + "</span> <span style='font-size:0.85em;'>" + escapeHtml(smart) + "</span></div>";
            }

            // Scribe Row highlighting
            String rowClass = isScribe ? "class='room-report-row scribe-row-highlight'" : "class='room-report-row'";
            String remarks = isScribe ? "SCRIBE" : "";

            sb.append("<tr ").append(rowClass).append(" style='height:2.1rem;'>");
            sb.append("<td class='sl-col' style='padding:0 4px; text-align:center;'>").append(escapeHtml(seatNumber)).append(asterisk).append("</td>");
            sb.append("<td class='course-col' style='padding:0 4px;'>").append(courseCellHtml).append("</td>");
            sb.append("<td class='reg-col' style='font-size:").append(regFontSize).append("; font-weight:bold; padding:0 4px;'>").append(escapeHtml(displayRegNo)).append("</td>");
            sb.append("<td class='name-col' style='padding:0 4px;'>").append(escapeHtml(getName(s))).append("</td>");
            sb.append("<td class='remarks-col' style='padding:0 4px; font-weight:bold;'>").append(remarks).append("</td>");
            sb.append("<td class='signature-col' style='padding:0 4px;'></td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }

    // =========================================================================
    // 4. CANDIDATE DAY-WISE SEATING (NOTICE BOARD - matches report4_examflow.js)
    // =========================================================================
    public static class DaywiseStudentItem {
        public String regNo;
        public String name;
        public String seat;
        public String course;
        public String displayRoom;
        public boolean isScribe;
        public String stream;

        public DaywiseStudentItem(String regNo, String name, String seat, String course, String displayRoom, boolean isScribe, String stream) {
            this.regNo = regNo;
            this.name = name;
            this.seat = seat;
            this.course = course;
            this.displayRoom = displayRoom;
            this.isScribe = isScribe;
            this.stream = stream;
        }
    }

    private static class DaywiseProcessedRow {
        DaywiseStudentItem item;
        boolean isCourseHeader;
        String displayRoom;
        String seatNo;
        String rowStyle = "";
        String courseName;
        int span = 1;
        boolean skipLocation = false;
    }

    public static String renderCandidateDaywiseReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> roomConfigs = dataSet.getRoomConfigs();
        Map<String, String> scribeAllotments = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());

        Set<String> globalScribeSet = new HashSet<>();
        for (JsonNode sc : dataSet.getScribes()) {
            String r = sc.path("regNo").asText("").trim();
            if (!r.isEmpty()) globalScribeSet.add(r);
        }

        // Build room serial map
        Map<String, Integer> roomSerialMap = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            roomSerialMap.put(rooms.get(i).path("roomName").asText("Hall " + (i + 1)), i + 1);
        }

        // Group students by Stream (Regular first, then alphabetical)
        Map<String, List<DaywiseStudentItem>> streamStudentsMap = new TreeMap<>((a, b) -> {
            if (a.equalsIgnoreCase(b)) return 0;
            if ("Regular".equalsIgnoreCase(a)) return -1;
            if ("Regular".equalsIgnoreCase(b)) return 1;
            return a.compareToIgnoreCase(b);
        });

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            String defaultStream = room.path("stream").asText("Regular");
            int serialNo = roomSerialMap.getOrDefault(roomName, 1);
            JsonNode cfg = roomConfigs.get(roomName);
            String loc = (cfg != null && cfg.has("location")) ? cfg.get("location").asText("").trim() : "";
            String standardDisplayRoom = !loc.isEmpty() ? loc : "Hall #" + serialNo;

            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String reg = getRegNo(s);
                    String name = getName(s);
                    String course = getCourse(s);
                    String stream = getStream(s, defaultStream);
                    String seat = s.path("seat").asText("-");
                    boolean isScribe = globalScribeSet.contains(reg) || scribeAllotments.containsKey(reg);

                    String actualDisplayRoom = standardDisplayRoom;
                    if (isScribe && scribeAllotments.containsKey(reg)) {
                        String sRoom = scribeAllotments.get(reg);
                        JsonNode sCfg = roomConfigs.get(sRoom);
                        String sLoc = (sCfg != null && sCfg.has("location")) ? sCfg.get("location").asText("").trim() : "";
                        int sSerial = roomSerialMap.getOrDefault(sRoom, serialNo);
                        actualDisplayRoom = !sLoc.isEmpty() ? sLoc : "Hall #" + sSerial;
                    }

                    DaywiseStudentItem item = new DaywiseStudentItem(reg, name, seat, course, actualDisplayRoom, isScribe, stream);
                    streamStudentsMap.computeIfAbsent(stream, k -> new ArrayList<>()).add(item);
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Seating Details for Candidates", false));

        if (streamStudentsMap.isEmpty()) {
            html.append("<div class='print-page'><div class='print-header-group text-center'>");
            html.append("<h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Seating Details for Candidates</h2><h3>").append(escapeHtml(sessionKey)).append("</h3></div>");
            html.append("<div style='text-align:center; padding:40px; font-weight:bold; color:#b91c1c;'>No candidate allotments found for session: ")
                .append(escapeHtml(sessionKey)).append("</div></div>");
        } else {
            int rowsPerCol = 35;
            int colsPerPage = 2;
            int studentsPerPage = rowsPerCol * colsPerPage; // 70 students per page
            int totalPagesGenerated = 0;

            for (Map.Entry<String, List<DaywiseStudentItem>> streamEntry : streamStudentsMap.entrySet()) {
                String streamName = streamEntry.getKey();
                List<DaywiseStudentItem> studentList = streamEntry.getValue();

                // Sort students: by Course (alphabetical), then by Register Number (alphabetical)
                studentList.sort((a, b) -> {
                    int cComp = a.course.compareToIgnoreCase(b.course);
                    if (cComp != 0) return cComp;
                    return a.regNo.compareToIgnoreCase(b.regNo);
                });

                String examName = dataSet.getExamName(sessionKey, streamName);
                String examNameHtml = !examName.isEmpty()
                        ? "<h2 style='font-size:13pt; font-weight:bold; margin:2px 0; text-transform:uppercase; text-align:center;'>" + escapeHtml(examName) + "</h2>"
                        : "";

                for (int i = 0; i < studentList.size(); i += studentsPerPage) {
                    int pEnd = Math.min(i + studentsPerPage, studentList.size());
                    List<DaywiseStudentItem> pageStudents = studentList.subList(i, pEnd);
                    totalPagesGenerated++;

                    int col1End = Math.min(rowsPerCol, pageStudents.size());
                    List<DaywiseStudentItem> col1 = pageStudents.subList(0, col1End);
                    List<DaywiseStudentItem> col2 = col1End < pageStudents.size() ? pageStudents.subList(col1End, pageStudents.size()) : Collections.emptyList();

                    String columnHtml;
                    if (col2.isEmpty()) {
                        columnHtml = "<div class='column' style='width:100%;'>" + buildColumnTable(col1) + "</div>";
                    } else {
                        columnHtml = "<div class='column-container' style='display:flex; gap:15px;'>" +
                                     "<div class='column' style='flex:1;'>" + buildColumnTable(col1) + "</div>" +
                                     "<div class='column' style='flex:1;'>" + buildColumnTable(col2) + "</div>" +
                                     "</div>";
                    }

                    html.append("<div class='print-page print-page-daywise'>");

                    // Page Header (identical to ExamFlow)
                    html.append("<div class='print-header-group' style='position:relative; margin-bottom:10px; text-align:center;'>");
                    html.append("<div style='position:absolute; top:0; left:0; font-weight:bold; font-size:11pt; border:1px solid #000; padding:2px 6px;'>")
                        .append("Page ").append(totalPagesGenerated).append("</div>");
                    html.append("<div style='position:absolute; top:0; right:0; font-weight:bold; font-size:11pt; border:1px solid #000; padding:2px 6px;'>")
                        .append(escapeHtml(streamName)).append("</div>");
                    html.append("<h1 style='font-size:14pt; font-weight:bold; margin:0; text-transform:uppercase;'>")
                        .append(escapeHtml(collegeName)).append("</h1>");
                    html.append(examNameHtml);
                    html.append("<h2 style='font-size:12pt; font-weight:bold; margin:2px 0;'>Seating Details</h2>");
                    html.append("<h3 style='font-size:10pt; margin:2px 0;'>").append(escapeHtml(sessionKey)).append("</h3>");
                    html.append("</div>");

                    html.append(columnHtml);
                    html.append("</div>"); // End page
                }

                // If this stream has scribes, append dedicated Scribe Assistance Summary Page
                List<DaywiseStudentItem> sessionScribes = new ArrayList<>();
                for (DaywiseStudentItem item : studentList) {
                    if (item.isScribe) sessionScribes.add(item);
                }
                if (!sessionScribes.isEmpty()) {
                    html.append(renderScribeSummaryPage(sessionScribes, streamName, sessionKey, collegeName, roomConfigs, scribeAllotments));
                }
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Builds a single column table applying the EXACT SMART MERGE algorithm from ExamFlow:
     * 1. Merges consecutive cells having the identical room location via rowspan.
     * 2. Breaks merges when a new course header is reached or location changes.
     * 3. Dynamically rotates text by 90 degrees with vertical writing-mode when span > 4.
     */
    private static String buildColumnTable(List<DaywiseStudentItem> studentChunk) {
        if (studentChunk == null || studentChunk.isEmpty()) {
            return "";
        }

        // 1. Pre-process data for RowSpans
        List<DaywiseProcessedRow> processedRows = new ArrayList<>();
        for (int i = 0; i < studentChunk.size(); i++) {
            DaywiseStudentItem student = studentChunk.get(i);
            String prevCourse = (i == 0) ? "" : studentChunk.get(i - 1).course;
            boolean isCourseHeader = !student.course.equalsIgnoreCase(prevCourse);

            String seatNo = student.seat;
            String rowStyle = "";
            if (student.isScribe) {
                seatNo = "SCR";
                rowStyle = "font-weight: bold; color: #c2410c;";
            }

            DaywiseProcessedRow pr = new DaywiseProcessedRow();
            pr.item = student;
            pr.isCourseHeader = isCourseHeader;
            pr.displayRoom = student.displayRoom;
            pr.seatNo = seatNo;
            pr.rowStyle = rowStyle;
            pr.courseName = student.course;
            pr.span = 1;
            pr.skipLocation = false;
            processedRows.add(pr);
        }

        // 2. Calculate Merges (Smart Merge: identical displayRoom across consecutive rows within course)
        for (int i = 0; i < processedRows.size(); i++) {
            DaywiseProcessedRow current = processedRows.get(i);
            if (current.skipLocation) continue;

            int span = 1;
            for (int j = i + 1; j < processedRows.size(); j++) {
                DaywiseProcessedRow next = processedRows.get(j);
                if (next.isCourseHeader || !next.displayRoom.equalsIgnoreCase(current.displayRoom)) {
                    break;
                }
                span++;
                next.skipLocation = true;
            }
            current.span = span;
        }

        // 3. Build HTML
        StringBuilder rowsHtml = new StringBuilder();
        for (DaywiseProcessedRow row : processedRows) {
            if (row.isCourseHeader) {
                rowsHtml.append("<tr>")
                        .append("<td colspan='4' style='background-color: #eee; font-weight: bold; padding: 2px 4px; border: 1px solid #000; font-size: 0.8em;'>")
                        .append(escapeHtml(row.courseName))
                        .append("</td></tr>");
            }

            rowsHtml.append("<tr style='").append(row.rowStyle).append("'>");

            if (!row.skipLocation) {
                String rowspanAttr = row.span > 1 ? ("rowspan='" + row.span + "'") : "";

                // Dynamic font and rotation logic (synced with ExamFlow HTML export)
                double dynFontSize = 9.0;
                int charLen = row.displayRoom.length();
                double spanHeightPx = row.span * 16.5;

                if (row.span > 4) {
                    int maxChars = row.span * 4;
                    if (charLen > maxChars + 8) dynFontSize = 6.5;
                    else if (charLen > maxChars) dynFontSize = 7.5;
                } else {
                    if (charLen > 25) dynFontSize = 6.5;
                    else if (charLen > 15) dynFontSize = 7.5;
                }

                String tdStyles;
                if (row.span > 4) {
                    tdStyles = "writing-mode: vertical-rl; transform: rotate(180deg); text-align: center; padding: 2px; height: " + spanHeightPx + "px; width: 100%; white-space: normal; overflow-wrap: break-word; word-break: break-all; line-height: 1.1; margin: auto; display: block;";
                } else {
                    tdStyles = "text-align: center; padding: 1px; white-space: normal; word-wrap: break-word; word-break: break-all; line-height: 1.05; margin: auto;";
                }

                rowsHtml.append("<td ").append(rowspanAttr).append(" style='background-color: #fff; border: 1px solid #000; vertical-align: middle; padding: 0; overflow: hidden; width: 55px;'>")
                        .append("<div style='").append(tdStyles).append(" font-size: ").append(dynFontSize).append("pt; font-weight: bold;'>")
                        .append(escapeHtml(row.displayRoom))
                        .append("</div></td>");
            }

            rowsHtml.append("<td style='padding: 1px 4px; font-weight: 700; font-size: 8.5pt; border: 1px solid #000; white-space: nowrap; overflow: hidden;'>")
                    .append(escapeHtml(row.item.regNo))
                    .append("</td>");

            rowsHtml.append("<td style='padding: 1px 4px; font-size: 7.5pt; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; border: 1px solid #000;'>")
                    .append(escapeHtml(row.item.name))
                    .append("</td>");

            rowsHtml.append("<td style='padding: 1px 2px; text-align: center; font-weight: bold; font-size: 6.5pt; white-space: nowrap; border: 1px solid #000;'>")
                    .append(escapeHtml(row.seatNo))
                    .append("</td>");

            rowsHtml.append("</tr>");
        }

        return "<table class='daywise-report-table' style='width: 100%; border-collapse: collapse; font-size: 8pt; table-layout: fixed;'>" +
               "<colgroup><col style='width: 55px;'><col style='width: 88px;'><col style='width: auto;'><col style='width: 28px;'></colgroup>" +
               "<thead><tr style='height: 1.3em;'>" +
               "<th style='border: 1px solid #000; padding: 1px 2px; font-size: 7pt;'>Loc</th>" +
               "<th style='border: 1px solid #000; padding: 1px 2px; font-size: 7pt;'>Reg No</th>" +
               "<th style='border: 1px solid #000; padding: 1px 2px; font-size: 7pt;'>Name</th>" +
               "<th style='border: 1px solid #000; padding: 1px 2px; font-size: 7pt;'>Seat</th>" +
               "</tr></thead><tbody>" +
               rowsHtml.toString() +
               "</tbody></table>";
    }

    private static String renderScribeSummaryPage(List<DaywiseStudentItem> scribes, String streamName,
                                                 String sessionKey, String collegeName,
                                                 Map<String, JsonNode> roomConfigs,
                                                 Map<String, String> scribeAllotments) {
        // Group Scribes by Room Location
        Map<String, List<DaywiseStudentItem>> scribesByRoom = new TreeMap<>();
        for (DaywiseStudentItem s : scribes) {
            String roomName = scribeAllotments.getOrDefault(s.regNo, "Unallotted");
            JsonNode roomInfo = roomConfigs.get(roomName);
            String loc = (roomInfo != null && roomInfo.has("location")) ? roomInfo.get("location").asText("").trim() : "";
            String locationDisplay = !loc.isEmpty() ? roomName + "<br/><span style='font-weight:normal; font-size:0.8em;'>(" + escapeHtml(loc) + ")</span>" : roomName;
            scribesByRoom.computeIfAbsent(locationDisplay, k -> new ArrayList<>()).add(s);
        }

        int totalScribes = scribes.size();
        int roomCount = scribesByRoom.size();
        double densityScore = (roomCount * 2.0) + (totalScribes * 0.5);

        String fontSize = "14pt";
        if (densityScore > 35) fontSize = "10pt";
        else if (densityScore > 25) fontSize = "11pt";
        else if (densityScore > 15) fontSize = "12pt";

        StringBuilder rowsHtml = new StringBuilder();
        for (Map.Entry<String, List<DaywiseStudentItem>> entry : scribesByRoom.entrySet()) {
            List<String> names = new ArrayList<>();
            for (DaywiseStudentItem st : entry.getValue()) {
                names.add("<b>" + escapeHtml(st.name) + "</b> (" + escapeHtml(st.regNo) + ")");
            }
            rowsHtml.append("<tr>")
                    .append("<td style='width:30%; vertical-align:top; padding:10px; border:1px solid #000;'>").append(entry.getKey()).append("</td>")
                    .append("<td style='width:70%; vertical-align:top; padding:10px; line-height:1.4; border:1px solid #000;'>").append(String.join(", ", names)).append("</td>")
                    .append("</tr>");
        }

        return "<div class='print-page' style='display:flex; flex-direction:column; justify-content:center; page-break-after:always;'>" +
               "<div class='print-header-group' style='margin-bottom:20px; text-align:center;'>" +
               "<h1 style='font-size:16pt; font-weight:bold; margin:0 0 5px 0;'>Scribe Assistance Summary</h1>" +
               "<h2 style='font-size:14pt; font-weight:bold; margin:0 0 5px 0; text-transform:uppercase;'>" + escapeHtml(collegeName) + "</h2>" +
               "<h3 style='font-size:11pt; font-weight:bold; margin:0;'>" + escapeHtml(sessionKey) + " (" + escapeHtml(streamName) + ")</h3>" +
               "</div>" +
               "<div style='border:2px solid #000; padding:5px; flex-grow:1;'>" +
               "<table style='width:100%; border-collapse:collapse; font-size:" + fontSize + ";'>" +
               "<thead><tr style='background-color:#eee; border-bottom:2px solid #000;'>" +
               "<th style='text-align:left; padding:10px; border:1px solid #000;'>Room Location</th>" +
               "<th style='text-align:left; padding:10px; border:1px solid #000;'>Candidates</th>" +
               "</tr></thead><tbody>" +
               rowsHtml.toString() +
               "</tbody></table></div>" +
               "<div style='text-align:right; margin-top:10px; font-size:10pt;'>" +
               "Total Scribes: <strong>" + totalScribes + "</strong>" +
               "</div></div>";
    }

    // =========================================================================
    // 5. ROOM STICKERS (2 PER PAGE - matches r5_stickers.js)
    // =========================================================================
    public static String renderRoomStickersReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> roomConfigs = dataSet.getRoomConfigs();

        List<String> stickers = new ArrayList<>();

        for (int rIdx = 0; rIdx < rooms.size(); rIdx++) {
            JsonNode room = rooms.get(rIdx);
            int serialNo = rIdx + 1;
            String roomName = room.path("roomName").asText("Hall " + serialNo);
            JsonNode cfg = roomConfigs.get(roomName);
            String loc = (cfg != null && cfg.has("location")) ? cfg.get("location").asText("").trim() : "";
            boolean hasLoc = !loc.isEmpty();
            String headerTitle = hasLoc ? loc : "Hall #" + serialNo;
            String roomSubTitle = hasLoc ? "<span style='font-size:12pt; font-weight:bold; margin-left:5px;'>(Hall #" + serialNo + ")</span>" : "";

            // Group students by course
            Map<String, List<JsonNode>> studentsByCourse = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            JsonNode students = room.path("students");
            int totalStudents = 0;
            if (students.isArray()) {
                totalStudents = students.size();
                for (JsonNode s : students) {
                    String c = getCourse(s);
                    studentsByCourse.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
                }
            }

            StringBuilder courseBlocksHtml = new StringBuilder();
            for (Map.Entry<String, List<JsonNode>> entry : studentsByCourse.entrySet()) {
                String courseName = entry.getKey();
                List<JsonNode> sList = entry.getValue();
                sList.sort(Comparator.comparingInt(s -> s.path("seat").asInt(999)));

                StringBuilder gridHtml = new StringBuilder();
                for (JsonNode s : sList) {
                    String seat = s.path("seat").asText("-");
                    String reg = getRegNo(s);
                    String name = getTruncatedName(getName(s), 18);

                    gridHtml.append("<div style='display:grid; grid-template-columns:25px max-content 1fr; align-items:center; border-bottom:1px dotted #ccc; padding:1px 0; font-size:8.5pt;'>");
                    gridHtml.append("<div style='text-align:center; font-weight:bold; border-right:1px solid #ddd;'>").append(escapeHtml(seat)).append("</div>");
                    gridHtml.append("<div style='text-align:left; font-weight:bold; padding-left:4px; padding-right:4px; border-right:1px solid #ddd; white-space:nowrap;'>").append(escapeHtml(reg)).append("</div>");
                    gridHtml.append("<div style='padding-left:4px; font-size:8pt; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; color:#333;'>").append(escapeHtml(name)).append("</div>");
                    gridHtml.append("</div>");
                }

                courseBlocksHtml.append("<div style='margin-bottom:3px; break-inside:avoid; border:1px solid #eee; padding:2px; background:#fafafa;'>");
                courseBlocksHtml.append("<div style='font-weight:bold; font-size:8.5pt; background:#e5e7eb; padding:1px 4px; margin-bottom:1px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis;'>")
                                .append(escapeHtml(courseName))
                                .append(" <span style='background:#fff; padding:0 3px; border-radius:4px; margin-left:3px; font-size:8pt; border:1px solid #ccc;'>")
                                .append(sList.size()).append("</span></div>");
                courseBlocksHtml.append("<div style='display:grid; grid-template-columns: 1fr 1fr 1fr; column-gap:6px;'>")
                                .append(gridHtml).append("</div>");
                courseBlocksHtml.append("</div>");
            }

            StringBuilder sticker = new StringBuilder();
            sticker.append("<div class='exam-sticker' style='border:2px dashed #000; padding:6px 8px; height:135mm; overflow:hidden; display:flex; flex-direction:column; box-sizing:border-box; background:white; width:100%;'>");

            sticker.append("<div style='text-align:center; margin-bottom:2px; flex-shrink:0; border-bottom:2px solid #000; padding-bottom:2px;'>");
            sticker.append("<h1 style='font-size:12pt; font-weight:bold; margin:0; text-transform:uppercase; line-height:1.1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;'>")
                   .append(escapeHtml(collegeName)).append("</h1>");
            sticker.append("<div style='font-size:9pt; font-weight:bold; margin-top:1px; color:#444;'>")
                   .append(escapeHtml(sessionKey)).append("</div>");
            sticker.append("<div style='margin-top:2px; border:2px solid #000; padding:2px 6px; display:flex; justify-content:center; align-items:center;'>");
            sticker.append("<span style='font-size:12pt; font-weight:bold; line-height:1.1; text-align:center;'>")
                   .append(escapeHtml(headerTitle)).append(" ").append(roomSubTitle).append("</span>");
            sticker.append("</div></div>");

            sticker.append("<div style='flex:1 1 auto; overflow:hidden; min-height:0; padding-top:2px;'>");
            sticker.append("<div style='display:block;'>").append(courseBlocksHtml).append("</div>");
            sticker.append("</div>");

            sticker.append("<div style='text-align:center; font-size:9pt; color:#000; margin-top:2px; flex-shrink:0; border-top:2px solid #000; padding-top:2px; font-weight:bold; background:#f0f0f0;'>");
            sticker.append("Total Candidates: ").append(totalStudents);
            sticker.append("</div></div>");

            stickers.add(sticker.toString());
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room Stickers (2 per Page)", false));

        for (int i = 0; i < stickers.size(); i += 2) {
            String s1 = stickers.get(i);
            String s2 = (i + 1 < stickers.size()) ? stickers.get(i + 1) : "";
            String gap = !s2.isEmpty() ? "<div class='sticker-gap' style='height:8px;'></div>" : "";

            html.append("<div class='print-page print-page-sticker' style='display:flex; flex-direction:column; justify-content:space-between; height:280mm; padding:8mm;'>");
            html.append(s1);
            html.append(gap);
            html.append(s2);
            html.append("</div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    // =========================================================================
    // 6. SCRIBE PROFORMA (ONE PAGE PER SCRIBE - matches r6_scribe_proforma.js)
    // =========================================================================
    public static String renderScribeProformaReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());

        // Map candidate regNo -> { student, originalRoom }
        Map<String, JsonNode> studentMap = new HashMap<>();
        Map<String, String> originalRoomMap = new HashMap<>();

        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String r = getRegNo(s);
                    if (!r.isEmpty()) {
                        studentMap.put(r, s);
                        originalRoomMap.put(r, roomName);
                    }
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Assistance Proforma", false));

        if (scribeAllot.isEmpty()) {
            html.append("<div class='print-page'><div class='print-header-group text-center'><h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Scribe Assistance Proforma</h2><h3>").append(escapeHtml(sessionKey)).append("</h3></div>");
            html.append("<div style='text-align:center; padding:40px; font-weight:bold;'>No scribe assistance allocated for session: ")
                .append(escapeHtml(sessionKey)).append("</div></div>");
        } else {
            for (Map.Entry<String, String> entry : scribeAllot.entrySet()) {
                String regNo = entry.getKey();
                String scribeRoom = entry.getValue();
                JsonNode student = studentMap.get(regNo);

                String name = student != null ? getName(student) : "Candidate " + regNo;
                String course = student != null ? getCourse(student) : "Allotted Paper";
                String stream = student != null ? getStream(student, "Regular") : "Regular";
                String origRoom = originalRoomMap.getOrDefault(regNo, "Main Hall");
                String qp = lookupQpCode(sessionQpCodes, course, stream);

                html.append("<div class='print-page'>");
                html.append("<div class='print-header-group' style='text-align:center; margin-bottom:1.5rem;'>");
                html.append("<h1 style='font-size:15pt; font-weight:bold; margin-bottom:0.3rem; text-transform:uppercase;'>")
                    .append(escapeHtml(collegeName)).append("</h1>");
                html.append("<h2 style='font-size:13pt; font-weight:bold; margin:0.3rem 0;'>Scribe Assistance Proforma</h2>");
                html.append("<h3 style='font-size:11pt; font-weight:bold; margin-top:0.3rem;'>")
                    .append(escapeHtml(sessionKey)).append("</h3>");
                html.append("</div>");

                html.append("<table class='proforma-table' style='width:100%; border-collapse:collapse; font-size:9.5pt;'><tbody>");

                html.append("<tr><td class='label' style='width:35%; font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Name of Candidate:</td>");
                html.append("<td class='data' style='padding:10px; border:1px solid #000; font-weight:bold;'>").append(escapeHtml(name)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Register Number:</td>");
                html.append("<td class='data' style='padding:10px; border:1px solid #000; font-family:monospace; font-weight:bold; font-size:11pt;'>").append(escapeHtml(regNo)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Course / Paper:</td>");
                html.append("<td class='data' style='padding:10px; border:1px solid #000;'>").append(escapeHtml(course)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>QP Code:</td>");
                html.append("<td class='data' style='padding:10px; border:1px solid #000; font-weight:bold;'>").append(escapeHtml(qp.isEmpty() ? "N/A" : qp)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Original Allotted Room:</td>");
                html.append("<td class='data' style='padding:10px; border:1px solid #000;'>").append(escapeHtml(origRoom)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-size:11pt; font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Scribe Allotted Room:</td>");
                html.append("<td class='data' style='font-size:1.3em; font-weight:800; border:2px solid #000; padding:10px; background-color:#f1f5f9;'>")
                    .append(escapeHtml(scribeRoom)).append("</td></tr>");

                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Sign or Thumb Impression of Candidate:</td><td class='data fillable' style='height:50px; border:1px solid #000;'></td></tr>");
                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Name of Scribe Assistant:</td><td class='data fillable' style='height:40px; border:1px solid #000;'></td></tr>");
                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Scribe Assistant ID Card &amp; No:</td><td class='data fillable' style='height:40px; border:1px solid #000;'></td></tr>");
                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Signature of Scribe Assistant:</td><td class='data fillable' style='height:40px; border:1px solid #000;'></td></tr>");
                html.append("<tr><td class='label' style='font-weight:bold; background:#eee; padding:10px; border:1px solid #000;'>Name &amp; Signature of Invigilator:</td><td class='data fillable' style='height:40px; border:1px solid #000;'></td></tr>");

                html.append("</tbody></table>");
                html.append("</div>");
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    // =========================================================================
    // 7. SCRIBE ASSISTANCE REPORT (matches r7_scribe_report.js)
    // =========================================================================
    public static String renderScribeAssistanceReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        Map<String, String> scribeAllot = dataSet.getScribeAllotments().getOrDefault(sessionKey, Collections.emptyMap());
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());

        Map<String, JsonNode> studentMap = new HashMap<>();
        Map<String, String> origRoomMap = new HashMap<>();
        for (JsonNode room : rooms) {
            String roomName = room.path("roomName").asText("Hall");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String r = getRegNo(s);
                    studentMap.put(r, s);
                    origRoomMap.put(r, roomName);
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Assistance Report", false));
        html.append("<div class='print-page'>");
        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:1.2rem;'>");
        html.append("<h1 style='font-size:15pt; font-weight:bold; margin-bottom:0.2rem; text-transform:uppercase;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2 style='font-size:13pt; font-weight:bold; margin:0.2rem 0;'>Scribe Assistance Report</h2>");
        html.append("<h3 style='font-size:11pt; font-weight:bold; margin-top:0.2rem;'>")
            .append(escapeHtml(sessionKey)).append("</h3>");
        html.append("</div>");

        if (scribeAllot.isEmpty()) {
            html.append("<div style='text-align:center; padding:40px; font-weight:bold;'>No scribe assistance entries for this session.</div>");
        } else {
            html.append("<table class='scribe-report-table' style='width:100%; border-collapse:collapse; font-size:9pt;'>");
            html.append("<thead><tr style='background:#eee;'>");
            html.append("<th style='width:5%; border:1px solid #000; padding:5px; text-align:center;'>Sl</th>");
            html.append("<th style='width:15%; border:1px solid #000; padding:5px;'>Register No</th>");
            html.append("<th style='width:20%; border:1px solid #000; padding:5px;'>Name</th>");
            html.append("<th style='width:20%; border:1px solid #000; padding:5px;'>Course / Paper</th>");
            html.append("<th style='width:10%; border:1px solid #000; padding:5px; text-align:center;'>QP Code</th>");
            html.append("<th style='width:15%; border:1px solid #000; padding:5px;'>Original Room</th>");
            html.append("<th style='width:15%; border:1px solid #000; padding:5px; font-weight:bold;'>Scribe Room</th>");
            html.append("</tr></thead><tbody>");

            int sl = 1;
            for (Map.Entry<String, String> e : scribeAllot.entrySet()) {
                String reg = e.getKey();
                String scribeRoom = e.getValue();
                JsonNode s = studentMap.get(reg);
                String name = s != null ? getName(s) : "-";
                String course = s != null ? getCourse(s) : "-";
                String stream = s != null ? getStream(s, "Regular") : "Regular";
                String origRoom = origRoomMap.getOrDefault(reg, "-");
                String qp = lookupQpCode(sessionQpCodes, course, stream);

                html.append("<tr>");
                html.append("<td style='text-align:center; border:1px solid #000; padding:4px;'>").append(sl++).append("</td>");
                html.append("<td style='font-weight:bold; font-family:monospace; border:1px solid #000; padding:4px;'>").append(escapeHtml(reg)).append("</td>");
                html.append("<td style='border:1px solid #000; padding:4px;'>").append(escapeHtml(name)).append("</td>");
                html.append("<td style='border:1px solid #000; padding:4px;'>").append(escapeHtml(course)).append("</td>");
                html.append("<td style='text-align:center; font-weight:bold; border:1px solid #000; padding:4px;'>").append(escapeHtml(qp.isEmpty() ? "-" : qp)).append("</td>");
                html.append("<td style='border:1px solid #000; padding:4px;'>").append(escapeHtml(origRoom)).append("</td>");
                html.append("<td style='font-weight:bold; border:1px solid #000; padding:4px;'>").append(escapeHtml(scribeRoom)).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
            html.append(getSignaturesFooter("Scribe Coordinator", "Chief Superintendent"));
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    // =========================================================================
    // 8. INVIGILATOR REQUIREMENT SUMMARY (matches r8_invigilator.js)
    // =========================================================================
    public static String renderInvigilatorRequirementReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<String> sessions = dataSet.getAllSessions();
        if (sessions.isEmpty()) {
            sessions = new ArrayList<>(dataSet.getRoomAllotments().keySet());
            sessions.sort(ActiveSessionResolver::compareSessions);
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Invigilator Requirement Summary", false));
        html.append("<div class='print-page'>");
        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:1.5rem;'>");
        html.append("<h1 style='font-size:16pt; font-weight:900; margin-bottom:0.3rem; text-transform:uppercase;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2 style='font-size:13pt; font-weight:bold; margin-top:0.3rem;'>Invigilator Requirement Summary</h2>");
        html.append("</div>");

        html.append("<table style='width:100%; border-collapse:collapse; font-size:9pt; border:1px solid #cbd5e1;'>");
        html.append("<thead><tr style='background:#f1f5f9; text-transform:uppercase; font-size:8pt; border-bottom:2px solid #94a3b8;'>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:22%;'>Date / Time</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1;'>Stream Requirements</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:110px; text-align:center;'>Scribes (1:5)</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:70px; text-align:center; color:#0f766e;'>Base</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:80px; text-align:center; color:#d97706;'>Reserve (10%)</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:70px; text-align:center; color:#be123c;'>Total</th>");
        html.append("</tr></thead><tbody>");

        int grandBase = 0;
        int grandReserve = 0;
        int grandTotal = 0;

        for (String sKey : sessions) {
            List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sKey, Collections.emptyList());
            Map<String, Integer> streamCounts = new TreeMap<>();
            int totalCandidates = 0;

            for (JsonNode r : rooms) {
                String strm = r.path("stream").asText("Regular");
                JsonNode stArr = r.path("students");
                int sz = stArr.isArray() ? stArr.size() : 0;
                totalCandidates += sz;
                streamCounts.put(strm, streamCounts.getOrDefault(strm, 0) + sz);
            }

            int scribeCount = dataSet.getScribeAllotments().getOrDefault(sKey, Collections.emptyMap()).size();
            int scribeInvigs = (int) Math.ceil((double) scribeCount / 5.0);

            StringBuilder streamBreakdown = new StringBuilder();
            int streamInvigTotal = 0;
            for (Map.Entry<String, Integer> se : streamCounts.entrySet()) {
                int c = se.getValue();
                int req = (int) Math.ceil((double) c / 30.0);
                streamInvigTotal += req;
                if (streamBreakdown.length() > 0) streamBreakdown.append("<br/>");
                streamBreakdown.append("<span style='font-weight:600;'>").append(escapeHtml(se.getKey())).append(":</span> ")
                               .append(c).append(" candidates &rarr; <strong style='color:#4f46e5;'>").append(req).append("</strong>");
            }

            int base = streamInvigTotal + scribeInvigs;
            int reserve = (int) Math.ceil(base * 0.10);
            int total = base + reserve;

            grandBase += base;
            grandReserve += reserve;
            grandTotal += total;

            html.append("<tr>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; font-weight:bold;'>").append(escapeHtml(sKey)).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; font-size:8.5pt;'>").append(streamBreakdown.toString()).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center;'>")
                .append(scribeCount > 0 ? scribeCount + " (" + scribeInvigs + ")" : "-").append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center; font-weight:bold; color:#0f766e;'>").append(base).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center; font-weight:bold; color:#d97706;'>").append(reserve).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center; font-weight:bold; font-size:10pt; color:#be123c;'>").append(total).append("</td>");
            html.append("</tr>");
        }

        html.append("<tr style='background:#f8fafc; font-weight:bold;'>");
        html.append("<td colspan='3' style='padding:8px; border:1px solid #cbd5e1; text-align:right;'>Grand Total:</td>");
        html.append("<td style='padding:8px; border:1px solid #cbd5e1; text-align:center; color:#0f766e;'>").append(grandBase).append("</td>");
        html.append("<td style='padding:8px; border:1px solid #cbd5e1; text-align:center; color:#d97706;'>").append(grandReserve).append("</td>");
        html.append("<td style='padding:8px; border:1px solid #cbd5e1; text-align:center; color:#be123c; font-size:11pt;'>").append(grandTotal).append("</td>");
        html.append("</tr>");

        html.append("</tbody></table>");

        // Footnote
        html.append("<div style='margin-top:1.5rem; padding:10px; background:#f8fafc; border:1px solid #e2e8f0; font-size:8pt; color:#475569; text-align:center;'>");
        html.append("<p style='margin:0;'><strong>Statutory Formula:</strong> 1 Invigilator per 30 Normal Candidates (calculated per stream) + 1 Invigilator per 5 Scribes + <strong>10% Reserve Buffer</strong>.</p>");
        html.append("</div>");

        html.append(getSignaturesFooter("Invigilation Superintendent", "Chief Superintendent"));
        html.append("</div></body></html>");
        return html.toString();
    }

    // =========================================================================
    // 9. ROOM ALLOTMENT SUMMARY (matches r9_room_summary.js)
    // =========================================================================
    public static String renderRoomAllotmentSummaryReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, JsonNode> configs = dataSet.getRoomConfigs();

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Room Allotment Summary", false));
        html.append("<div class='print-page'>");
        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:1.2rem;'>");
        html.append("<h1 style='font-size:15pt; font-weight:bold; margin-bottom:0.2rem; text-transform:uppercase;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h2 style='font-size:13pt; font-weight:bold; margin:0.2rem 0;'>Room Allotment Summary</h2>");
        html.append("<h3 style='font-size:11pt; font-weight:bold; margin-top:0.2rem;'>")
            .append(escapeHtml(sessionKey)).append("</h3>");
        html.append("</div>");

        html.append("<table class='w-full border-collapse border border-black text-sm' style='width:100%; border-collapse:collapse; border:1px solid #000; font-size:9pt;'>");
        html.append("<thead><tr style='background:#e5e7eb;'>");
        html.append("<th style='padding:6px; border:1px solid #000; text-align:center; width:80px;'>Serial No</th>");
        html.append("<th style='padding:6px; border:1px solid #000; text-align:left;'>Location / Room</th>");
        html.append("<th style='padding:6px; border:1px solid #000; text-align:center; width:140px;'>Students Allotted</th>");
        html.append("</tr></thead><tbody>");

        int sessionTotalStudents = 0;
        for (int i = 0; i < rooms.size(); i++) {
            JsonNode room = rooms.get(i);
            int serialNo = i + 1;
            String roomName = room.path("roomName").asText("Hall " + serialNo);
            JsonNode cfg = configs.get(roomName);
            String loc = (cfg != null && cfg.has("location")) ? cfg.get("location").asText("").trim() : "";
            String displayRoom = !loc.isEmpty() ? loc + " (" + roomName + ")" : roomName;

            JsonNode students = room.path("students");
            int count = students.isArray() ? students.size() : 0;
            sessionTotalStudents += count;

            html.append("<tr>");
            html.append("<td style='padding:6px; border:1px solid #000; text-align:center; font-weight:bold;'>").append(serialNo).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #000;'>").append(escapeHtml(displayRoom)).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #000; text-align:center; font-weight:bold;'>").append(count).append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody><tfoot>");
        html.append("<tr style='background:#f3f4f6;'>");
        html.append("<td colspan='2' style='padding:8px; border:1px solid #000; text-align:right; font-weight:bold;'>");
        html.append("SESSION TOTAL (").append(rooms.size()).append(" Rooms):</td>");
        html.append("<td style='padding:8px; border:1px solid #000; text-align:center; font-weight:bold; font-size:1.1em;'>")
            .append(sessionTotalStudents).append("</td>");
        html.append("</tr></tfoot></table>");

        html.append("<div style='margin-top:40px; display:flex; justify-content:space-between; align-items:flex-end;'>");
        html.append("<div style='font-size:8.5pt; color:#666;'>Generated by ExamFlow</div>");
        html.append("<div style='text-align:center; width:200px;'>");
        html.append("<div style='border-top:1px solid #000; padding-top:4px; font-weight:bold; font-size:9pt;'>Chief Superintendent</div>");
        html.append("</div></div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    // =========================================================================
    // 10. SCRIBE MASTER REPORT (matches r10_scribe_master.js)
    // =========================================================================
    public static String renderScribeMasterReport(ExamFlowDataSet dataSet) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> scribes = dataSet.getScribes();

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Scribe Assistance Master Report", false));
        html.append("<div class='print-page'>");

        html.append("<div class='print-header-group text-center' style='text-align:center; margin-bottom:1.5rem;'>");
        html.append("<h1 style='font-size:18pt; font-weight:900; text-transform:uppercase; margin-bottom:0.2rem;'>")
            .append(escapeHtml(collegeName)).append("</h1>");
        html.append("<h3 style='font-size:13pt; font-weight:900; text-transform:uppercase; color:#831843; margin-top:0.2rem;'>")
            .append("Scribe Assistance Master Report</h3>");
        html.append("<div style='font-size:9pt; color:#666; margin-top:4px;'>Registered Scribes: <strong>")
            .append(scribes.size()).append(" Candidates</strong></div>");
        html.append("</div>");

        html.append("<table style='width:100%; border-collapse:collapse; border:1px solid #cbd5e1; font-size:9.5pt;'>");
        html.append("<thead><tr style='background:#f1f5f9; text-transform:uppercase; font-size:8pt;'>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:70px; text-align:center;'>Sl.No</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; width:180px; text-align:center;'>Register Number</th>");
        html.append("<th style='padding:8px; border:1px solid #cbd5e1; text-align:left;'>Student Name</th>");
        html.append("</tr></thead><tbody>");

        int sl = 1;
        for (JsonNode sc : scribes) {
            String reg = sc.path("regNo").asText("-");
            String name = sc.path("name").asText("-");

            html.append("<tr>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center;'>").append(sl++).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; text-align:center; font-family:monospace; font-weight:900; color:#be185d; font-size:10pt;'>")
                .append(escapeHtml(reg)).append("</td>");
            html.append("<td style='padding:6px; border:1px solid #cbd5e1; font-weight:bold;'>").append(escapeHtml(name)).append("</td>");
            html.append("</tr>");
        }

        if (scribes.isEmpty()) {
            html.append("<tr><td colspan='3' style='text-align:center; padding:30px; font-weight:bold;'>No scribes registered in the system.</td></tr>");
        }

        html.append("</tbody></table>");

        html.append("<div style='margin-top:40px; display:flex; justify-content:space-between; align-items:flex-end;'>");
        html.append("<div style='font-size:8.5pt; color:#666; font-style:italic;'>Generated via ExamFlow Orchestrator</div>");
        html.append("<div style='text-align:center; width:220px;'>");
        html.append("<div style='border-top:2px solid #000; padding-top:4px; font-weight:900; font-size:9.5pt; text-transform:uppercase;'>Chief Superintendent</div>");
        html.append("</div></div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    // =========================================================================
    // 11. ABSENTEE STATEMENT (STATEMENT OF ANSWER SCRIPTS - matches r11_absentee.js)
    // =========================================================================
    public static String renderAbsenteeStatementReport(ExamFlowDataSet dataSet, String sessionKey) {
        String collegeName = dataSet.getCollegeName();
        List<JsonNode> rooms = dataSet.getRoomAllotments().getOrDefault(sessionKey, Collections.emptyList());
        Map<String, String> sessionQpCodes = dataSet.getQpCodes().getOrDefault(sessionKey, Collections.emptyMap());
        List<String> absList = dataSet.getAbsenteeList().getOrDefault(sessionKey, Collections.emptyList());
        Set<String> absSet = new HashSet<>(absList);

        // Group by Stream -> QP Code -> CourseData { name, present[], absent[] }
        class QpGroup {
            String stream;
            String qpCode;
            Map<String, List<String>> coursePresent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, List<String>> courseAbsent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        Map<String, QpGroup> qpGroups = new LinkedHashMap<>();

        for (JsonNode room : rooms) {
            String roomStream = room.path("stream").asText("Regular");
            JsonNode students = room.path("students");
            if (students.isArray()) {
                for (JsonNode s : students) {
                    String reg = getRegNo(s);
                    String course = getCourse(s);
                    String stream = getStream(s, roomStream);
                    String rawQp = lookupQpCode(sessionQpCodes, course, stream);
                    String qp = rawQp.isEmpty() ? "NO-QP" : rawQp;

                    String groupKey = stream + "___" + qp;
                    QpGroup grp = qpGroups.get(groupKey);
                    if (grp == null) {
                        grp = new QpGroup();
                        grp.stream = stream;
                        grp.qpCode = qp;
                        qpGroups.put(groupKey, grp);
                    }

                    if (absSet.contains(reg)) {
                        grp.courseAbsent.computeIfAbsent(course, k -> new ArrayList<>()).add(reg);
                    } else {
                        grp.coursePresent.computeIfAbsent(course, k -> new ArrayList<>()).add(reg);
                    }
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append(getBaseHtmlHead("Statement of Answer Scripts", false));

        if (qpGroups.isEmpty()) {
            html.append("<div class='print-page'><div class='print-header-group text-center'><h1>").append(escapeHtml(collegeName)).append("</h1>");
            html.append("<h2>Statement of Answer Scripts</h2><h3>").append(escapeHtml(sessionKey)).append("</h3></div>");
            html.append("<div style='text-align:center; padding:30px; font-weight:bold;'>No candidate allotments found.</div></div>");
        } else {
            for (QpGroup grp : qpGroups.values()) {
                int grandPresent = 0;
                int grandAbsent = 0;

                Set<String> allCourses = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                allCourses.addAll(grp.coursePresent.keySet());
                allCourses.addAll(grp.courseAbsent.keySet());

                StringBuilder tableRowsHtml = new StringBuilder();

                for (String cName : allCourses) {
                    List<String> present = grp.coursePresent.getOrDefault(cName, Collections.emptyList());
                    List<String> absent = grp.courseAbsent.getOrDefault(cName, Collections.emptyList());
                    grandPresent += present.size();
                    grandAbsent += absent.size();

                    String presentListText = formatRegNoList(present);
                    String absentListText = formatRegNoList(absent);

                    tableRowsHtml.append("<tr><td colspan='2' style='font-weight:bold; border:1px solid #000; padding:6px; background:#f3f4f6;'>Course: ")
                                 .append(escapeHtml(cName)).append("</td></tr>");

                    tableRowsHtml.append("<tr><td style='vertical-align:top; width:22%; padding:6px; border:1px solid #000;'><strong>Present (")
                                 .append(present.size()).append(")</strong></td>");
                    tableRowsHtml.append("<td class='regno-list' style='vertical-align:top; padding:6px; border:1px solid #000; font-size:8.5pt; line-height:1.4; white-space:pre-wrap;'>")
                                 .append(escapeHtml(presentListText)).append("</td></tr>");

                    tableRowsHtml.append("<tr><td style='vertical-align:top; padding:6px; border:1px solid #000;'><strong>Absent (")
                                 .append(absent.size()).append(")</strong></td>");
                    tableRowsHtml.append("<td class='regno-list' style='vertical-align:top; padding:6px; border:1px solid #000; font-size:8.5pt; line-height:1.4; white-space:pre-wrap;'>")
                                 .append(escapeHtml(absentListText)).append("</td></tr>");
                }

                // Summary Row
                int grandTotal = grandPresent + grandAbsent;
                tableRowsHtml.append("<tr style='border-top:2px solid #000;'><td colspan='2' style='padding:10px; border:1px solid #000;'>");
                tableRowsHtml.append("<div style='display:flex; justify-content:space-between; font-weight:bold; font-size:1.05em;'>");
                tableRowsHtml.append("<span>QP CODE: ").append(escapeHtml(grp.qpCode)).append("</span>");
                tableRowsHtml.append("<span>Total: ").append(grandTotal)
                             .append(" &nbsp;|&nbsp; Present: ").append(grandPresent)
                             .append(" &nbsp;|&nbsp; Absent: ").append(grandAbsent).append("</span>");
                tableRowsHtml.append("</div></td></tr>");

                html.append("<div class='print-page'>");
                html.append("<div class='print-header-group' style='position:relative; border-bottom:2px solid #000; padding-bottom:8px; margin-bottom:12px; padding-top:25px; text-align:center;'>");
                html.append("<div style='position:absolute; top:0; right:0; font-weight:bold; font-size:11pt; border:2px solid #000; padding:2px 8px;'>")
                    .append(escapeHtml(grp.stream)).append("</div>");
                html.append("<h1 style='font-size:15pt; font-weight:bold; margin:0; text-transform:uppercase;'>")
                    .append(escapeHtml(collegeName)).append("</h1>");
                html.append("<h2 style='font-size:13pt; font-weight:bold; margin:2px 0;'>Statement of Answer Scripts</h2>");
                html.append("<h3 style='font-size:11pt; font-weight:bold; margin:2px 0;'>").append(escapeHtml(sessionKey)).append("</h3>");
                html.append("<div style='margin-top:6px; font-weight:bold; font-size:13pt;'>QP Code: <span style='padding:2px 8px; border:1px solid #000;'>")
                    .append(escapeHtml(grp.qpCode)).append("</span></div>");
                html.append("</div>");

                html.append("<table class='absentee-report-table' style='width:100%; border-collapse:collapse; border:1px solid #000; font-size:9pt;'><tbody>");
                html.append(tableRowsHtml);
                html.append("</tbody></table>");

                html.append("<div class='absentee-footer' style='margin-top:40px; display:flex; justify-content:space-between; align-items:flex-end;'>");
                html.append("<div style='font-size:9pt;'><strong>Generated by ExamFlow</strong></div>");
                html.append("<div class='signature' style='text-align:center; width:200px; border-top:1px solid #000; padding-top:4px; font-weight:bold;'>Chief Superintendent</div>");
                html.append("</div></div>");
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    // =========================================================================
    // FORMATTING HELPERS & REGNO COMPRESSION (matches ExamFlow)
    // =========================================================================
    public static String formatRegNoList(List<String> regNos) {
        if (regNos == null || regNos.isEmpty()) return "None";

        List<String> sorted = new ArrayList<>(regNos);
        Collections.sort(sorted);

        List<String> outputLines = new ArrayList<>();
        String currentPrefix = "";
        List<String> numberGroup = new ArrayList<>();

        for (String reg : sorted) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-zA-Z\\-_]*)(\\d+)$").matcher(reg);
            if (m.matches()) {
                String prefix = m.group(1);
                String number = m.group(2);
                if (prefix.equals(currentPrefix)) {
                    numberGroup.add(number);
                } else {
                    if (!numberGroup.isEmpty()) {
                        outputLines.add(String.join(", ", numberGroup));
                        numberGroup.clear();
                    }
                    currentPrefix = prefix;
                    numberGroup.add(reg);
                }
            } else {
                if (!numberGroup.isEmpty()) {
                    outputLines.add(String.join(", ", numberGroup));
                    numberGroup.clear();
                }
                currentPrefix = "";
                outputLines.add(reg);
            }
        }
        if (!numberGroup.isEmpty()) {
            outputLines.add(String.join(", ", numberGroup));
        }

        return String.join("\n", outputLines);
    }

    public static String getSmartCourseName(String fullName) {
        if (fullName == null) return "";
        // Extract syllabus info before cleaning (looks for 4 digits inside brackets)
        java.util.regex.Matcher syllabusMatch = java.util.regex.Pattern.compile("\\[(.*?[0-9]{4}.*?)\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(fullName);
        String syllabus = syllabusMatch.find() ? syllabusMatch.group(1).trim() : null;

        String cleanName = fullName.replaceAll("\\[.*?\\]", "").trim();
        cleanName = cleanName.replaceAll("\\s-\\s$", "").trim();

        String[] words = cleanName.split("\\s+");
        String resultName = cleanName;
        if (words.length > 4) {
            resultName = words[0] + " " + words[1] + " " + words[2] + " ... " + words[words.length - 1];
        }

        if (syllabus != null) {
            resultName += " [" + syllabus + "]";
        }
        return resultName;
    }

    public static String getTruncatedName(String name, int maxChars) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.length() <= maxChars) return clean;
        return clean.substring(0, maxChars - 2) + "..";
    }

    public static String getBaseHtmlHead(String title, boolean duplex) {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset='utf-8'/>\n<title>" + escapeHtml(title) + "</title>\n" +
               "<style>\n" +
               "@page { size: A4 portrait; margin: 0; }\n" +
               "* { box-sizing: border-box; }\n" +
               "html, body { margin: 0; padding: 0; background: #f1f5f9; color: #111827; font-family: Arial, Helvetica, sans-serif; font-size: 9pt; line-height: 1.35; }\n" +
               ".print-page { width: 210mm; min-height: 297mm; box-sizing: border-box; background: white; box-shadow: 0 4px 15px rgba(0, 0, 0, 0.12); margin: 10px auto 25px auto; padding: 10mm 12mm; position: relative; page-break-after: always; }\n" +
               ".print-page:last-child { page-break-after: auto; }\n" +
               ".print-page-room { padding: 10mm 12mm !important; }\n" +
               ".print-page-daywise { width: 210mm; min-height: 297mm; box-sizing: border-box; padding: 8mm 10mm !important; margin: 10px auto 25px auto; background: white; box-shadow: 0 4px 15px rgba(0, 0, 0, 0.12); page-break-after: always; page-break-inside: avoid; font-family: Arial, sans-serif; }\n" +
               ".print-page-sticker { width: 210mm; min-height: 297mm; box-sizing: border-box; padding: 6mm !important; margin: 10px auto 25px auto; background: white; box-shadow: 0 4px 15px rgba(0, 0, 0, 0.12); }\n" +
               ".print-table { width: 100%; border-collapse: collapse; font-size: 8.5pt; margin-top: 6px; }\n" +
               ".print-table th, .print-table td { border: 1px solid #000; padding: 3px 5px; text-align: left; word-wrap: break-word; }\n" +
               ".print-table th { background-color: #eee; font-weight: bold; }\n" +
               ".room-report-row { height: 2.1rem !important; }\n" +
               ".room-report-row td { height: 2.1rem !important; overflow: hidden; white-space: nowrap; }\n" +
               ".scribe-row-highlight { background-color: #374151 !important; color: white !important; -webkit-print-color-adjust: exact; print-color-adjust: exact; font-weight: bold; }\n" +
               ".scribe-row-highlight td { border: 1px solid #ffffff !important; }\n" +
               ".scribe-row-highlight td.signature-col { background-color: #ffffff !important; border: 1px solid #000000 !important; }\n" +
               ".daywise-report-table { border-collapse: collapse; width: 100%; table-layout: fixed; }\n" +
               ".daywise-report-table td, .daywise-report-table th { padding: 0 3px; font-size: 8pt; line-height: 1.35; border: 1px solid #000; overflow: hidden; }\n" +
               ".daywise-report-table th { font-size: 7.5pt; background: #eee; text-align: center; }\n" +
               ".q-paper-table .course-col { width: 70%; }\n" +
               ".q-paper-table .count-col { width: 20%; }\n" +
               ".proforma-table { width: 100%; border-collapse: collapse; font-size: 9.5pt; margin-top: 15px; }\n" +
               ".proforma-table td { padding: 10px; border: 1px solid #000; }\n" +
               ".proforma-table td.label { width: 35%; font-weight: bold; background-color: #eee !important; -webkit-print-color-adjust: exact; }\n" +
               ".proforma-table td.fillable { height: 45px; }\n" +
               ".scribe-report-table { width: 100%; border-collapse: collapse; font-size: 9pt; margin-top: 1rem; }\n" +
               ".scribe-report-table th, .scribe-report-table td { border: 1px solid #000; padding: 5px 6px; }\n" +
               ".scribe-report-table th { background: #eee; }\n" +
               ".absentee-report-table { width: 100%; border-collapse: collapse; border: 1px solid #000; font-size: 9pt; }\n" +
               ".absentee-report-table td, .absentee-report-table th { border: 1px solid #000; padding: 6px 8px; }\n" +
               "@media print {\n" +
               "  html, body { height: auto !important; overflow: visible !important; display: block !important; margin: 0 !important; padding: 0 !important; background: white !important; }\n" +
               "  .print-page { width: 100% !important; max-width: none !important; min-height: auto !important; margin: 0 !important; padding: 8mm 10mm !important; box-shadow: none !important; border: none !important; page-break-after: always !important; page-break-inside: avoid !important; break-inside: avoid !important; break-after: page !important; }\n" +
               "  .print-page:last-child { page-break-after: auto !important; break-after: auto !important; }\n" +
               "  .print-page-room { padding: 8mm 10mm !important; }\n" +
               "  .print-page-daywise { width: 100% !important; max-width: none !important; min-height: auto !important; max-height: none !important; padding: 6mm 8mm !important; box-shadow: none !important; border: none !important; page-break-after: always !important; page-break-inside: avoid !important; }\n" +
               "  .print-page-sticker { padding: 5mm !important; }\n" +
               "  body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n" +
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

    public static String getName(JsonNode s) {
        if (s == null || s.isNull()) return "";
        for (String k : new String[]{"Name", "StudentName", "student_name", "candidateName"}) {
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

    public static String getStream(JsonNode s, String defaultStream) {
        if (s != null) {
            for (String k : new String[]{"Stream", "stream"}) {
                if (s.has(k)) {
                    String val = s.get(k).asText("").trim();
                    if (!val.isEmpty()) return val;
                }
            }
        }
        return (defaultStream != null && !defaultStream.trim().isEmpty()) ? defaultStream.trim() : "Regular";
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
