package com.printmanager.reports.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printmanager.reports.model.ExamFlowDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.util.*;

public class ExamFlowDataBridge {
    private static final Logger logger = LoggerFactory.getLogger(ExamFlowDataBridge.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static ExamFlowDataSet currentDataSet = new ExamFlowDataSet();

    public static synchronized ExamFlowDataSet getCurrentDataSet() {
        if (currentDataSet == null || currentDataSet.isEmpty()) {
            tryAutoLoadLatestBackup();
        }
        return currentDataSet;
    }

    public static synchronized void tryAutoLoadLatestBackup() {
        try {
            String userHome = System.getProperty("user.home", "");
            List<File> searchDirs = new ArrayList<>();
            if (!userHome.isEmpty()) {
                searchDirs.add(new File(userHome, "Downloads"));
                searchDirs.add(new File(userHome, "OneDrive/Documents/Downloads"));
            }

            File newestBackup = null;
            long latestTime = 0;

            for (File dir : searchDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) -> name.startsWith("ExamFlow") && name.endsWith(".json"));
                    if (files != null) {
                        for (File f : files) {
                            if (f.lastModified() > latestTime) {
                                latestTime = f.lastModified();
                                newestBackup = f;
                            }
                        }
                    }
                }
            }

            if (newestBackup != null && newestBackup.exists()) {
                logger.info("Auto-loading latest ExamFlow backup: {}", newestBackup.getAbsolutePath());
                loadFromFile(newestBackup);
            }
        } catch (Exception e) {
            logger.warn("Auto-load of ExamFlow backup failed: {}", e.getMessage());
        }
    }

    public static synchronized void setCurrentDataSet(ExamFlowDataSet dataSet) {
        if (dataSet != null) {
            currentDataSet = dataSet;
            logger.info("ExamFlowDataSet updated: College '{}', {} sessions, {} room allotments",
                    dataSet.getCollegeName(), dataSet.getAllSessions().size(), dataSet.getRoomAllotments().size());
        }
    }

    /**
     * Parses an ExamFlow JSON backup, Firestore payload, or in-browser exported JSON into ExamFlowDataSet.
     */
    public static ExamFlowDataSet parseExamFlowJson(String jsonText) throws Exception {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            throw new IllegalArgumentException("ExamFlow JSON content is empty.");
        }
        JsonNode root = mapper.readTree(jsonText);
        return parseExamFlowJson(root);
    }

    public static ExamFlowDataSet parseExamFlowJson(JsonNode root) {
        ExamFlowDataSet dataSet = new ExamFlowDataSet();

        // 1. College Name
        JsonNode collegeNode = findNode(root, "examCollegeName", "collegeName", "currentCollegeName");
        if (collegeNode != null && !collegeNode.isNull()) {
            String col = unwrapText(collegeNode);
            if (col != null && !col.isEmpty()) {
                dataSet.setCollegeName(col);
            }
        }

        // 2. All Known Sessions
        List<String> sessions = new ArrayList<>();
        JsonNode sessionsNode = findNode(root, "examAllKnownSessions", "allSessions", "sessions");
        if (sessionsNode != null) {
            JsonNode sArr = unwrapJson(sessionsNode);
            if (sArr != null && sArr.isArray()) {
                for (JsonNode s : sArr) {
                    String sStr = s.asText("").trim();
                    if (!sStr.isEmpty() && !sessions.contains(sStr)) {
                        sessions.add(sStr);
                    }
                }
            }
        }

        // 3. Room Allotment (Session -> List of Rooms)
        Map<String, List<JsonNode>> allotments = new HashMap<>();
        JsonNode allotNode = findNode(root, "examRoomAllotment", "roomAllotment", "allotments");
        if (allotNode != null) {
            JsonNode allotObj = unwrapJson(allotNode);
            if (allotObj != null && allotObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = allotObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String sessionKey = entry.getKey();
                    if (!sessions.contains(sessionKey)) {
                        sessions.add(sessionKey);
                    }
                    List<JsonNode> roomList = new ArrayList<>();
                    if (entry.getValue().isArray()) {
                        for (JsonNode r : entry.getValue()) {
                            roomList.add(r);
                        }
                    }
                    allotments.put(sessionKey, roomList);
                }
            }
        }
        dataSet.setRoomAllotments(allotments);

        // 4. QP Codes (Session -> Base64Key -> QP Code)
        Map<String, Map<String, String>> qpMap = new HashMap<>();
        JsonNode qpNode = findNode(root, "examQPCodes", "qpCodes", "qpCodeMap");
        if (qpNode != null) {
            JsonNode qpObj = unwrapJson(qpNode);
            if (qpObj != null && qpObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = qpObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> sEntry = it.next();
                    String sessionKey = sEntry.getKey();
                    Map<String, String> codeMap = new HashMap<>();
                    JsonNode sCodes = sEntry.getValue();
                    if (sCodes.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> codeIt = sCodes.fields();
                        while (codeIt.hasNext()) {
                            Map.Entry<String, JsonNode> cEntry = codeIt.next();
                            codeMap.put(cEntry.getKey(), cEntry.getValue().asText(""));
                        }
                    }
                    qpMap.put(sessionKey, codeMap);
                }
            }
        }
        dataSet.setQpCodes(qpMap);

        // 5. Room Configs (RoomName -> {capacity, location})
        Map<String, JsonNode> roomConfigs = new HashMap<>();
        JsonNode rcNode = findNode(root, "examRoomConfig", "roomConfig", "rooms");
        if (rcNode != null) {
            JsonNode rcObj = unwrapJson(rcNode);
            if (rcObj != null && rcObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = rcObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    roomConfigs.put(entry.getKey(), entry.getValue());
                }
            }
        }
        dataSet.setRoomConfigs(roomConfigs);

        // 6. Scribes
        List<JsonNode> scribes = new ArrayList<>();
        JsonNode scNode = findNode(root, "examScribeList", "scribes", "scribeList");
        if (scNode != null) {
            JsonNode scArr = unwrapJson(scNode);
            if (scArr != null && scArr.isArray()) {
                for (JsonNode s : scArr) scribes.add(s);
            }
        }
        dataSet.setScribes(scribes);

        // 7. Scribe Allotments (Session -> RegNo -> RoomName)
        Map<String, Map<String, String>> scribeAllot = new HashMap<>();
        JsonNode saNode = findNode(root, "examScribeAllotment", "scribeAllotment");
        if (saNode != null) {
            JsonNode saObj = unwrapJson(saNode);
            if (saObj != null && saObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = saObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> sEntry = it.next();
                    Map<String, String> regMap = new HashMap<>();
                    if (sEntry.getValue().isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> regIt = sEntry.getValue().fields();
                        while (regIt.hasNext()) {
                            Map.Entry<String, JsonNode> regEntry = regIt.next();
                            regMap.put(regEntry.getKey(), regEntry.getValue().asText(""));
                        }
                    }
                    scribeAllot.put(sEntry.getKey(), regMap);
                }
            }
        }
        dataSet.setScribeAllotments(scribeAllot);

        // 8. Staff Data
        List<JsonNode> staff = new ArrayList<>();
        JsonNode stNode = findNode(root, "examStaffData", "staffData", "staffList");
        if (stNode != null) {
            JsonNode stArr = unwrapJson(stNode);
            if (stArr != null && stArr.isArray()) {
                for (JsonNode s : stArr) staff.add(s);
            }
        }
        dataSet.setStaffList(staff);

        // 9. Invigilator Mappings (Session -> Room -> StaffName)
        Map<String, Map<String, String>> invigMap = new HashMap<>();
        JsonNode imNode = findNode(root, "examInvigilatorMapping", "invigilatorMapping");
        if (imNode != null) {
            JsonNode imObj = unwrapJson(imNode);
            if (imObj != null && imObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = imObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> sEntry = it.next();
                    Map<String, String> roomStaff = new HashMap<>();
                    if (sEntry.getValue().isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> rIt = sEntry.getValue().fields();
                        while (rIt.hasNext()) {
                            Map.Entry<String, JsonNode> rEntry = rIt.next();
                            roomStaff.put(rEntry.getKey(), rEntry.getValue().asText(""));
                        }
                    }
                    invigMap.put(sEntry.getKey(), roomStaff);
                }
            }
        }
        dataSet.setInvigilatorMappings(invigMap);

        // 10. Absentee List (Session -> List<RegNo>)
        Map<String, List<String>> absenteeMap = new HashMap<>();
        JsonNode abNode = findNode(root, "examAbsenteeList", "absenteeList", "absentees");
        if (abNode != null) {
            JsonNode abObj = unwrapJson(abNode);
            if (abObj != null && abObj.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = abObj.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> sEntry = it.next();
                    List<String> absList = new ArrayList<>();
                    if (sEntry.getValue().isArray()) {
                        for (JsonNode a : sEntry.getValue()) {
                            absList.add(a.asText(""));
                        }
                    }
                    absenteeMap.put(sEntry.getKey(), absList);
                }
            }
        }
        dataSet.setAbsenteeList(absenteeMap);

        // 11. Base Student Data (Nominal roll)
        List<JsonNode> students = new ArrayList<>();
        JsonNode studNode = findNode(root, "examBaseData", "allStudentData", "allStudents", "students");
        if (studNode != null) {
            JsonNode studArr = unwrapJson(studNode);
            if (studArr != null && studArr.isArray()) {
                for (JsonNode st : studArr) students.add(st);
            }
        }
        dataSet.setAllStudents(students);

        // 12. Exam Rules / Exam Names
        List<JsonNode> rules = new ArrayList<>();
        JsonNode ruleNode = findNode(root, "examRulesConfig", "rulesConfig", "rules");
        if (ruleNode != null) {
            JsonNode ruleArr = unwrapJson(ruleNode);
            if (ruleArr != null && ruleArr.isArray()) {
                for (JsonNode r : ruleArr) rules.add(r);
            }
        }
        dataSet.setExamRulesConfig(rules);

        // Sort all discovered sessions chronologically
        sessions.sort(ActiveSessionResolver::compareSessions);
        dataSet.setAllSessions(sessions);

        return dataSet;
    }

    /**
     * Loads and parses an ExamFlow JSON file from local disk.
     */
    public static ExamFlowDataSet loadFromFile(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + file);
        }
        JsonNode root = mapper.readTree(file);
        ExamFlowDataSet dataSet = parseExamFlowJson(root);
        setCurrentDataSet(dataSet);
        return dataSet;
    }

    /**
     * Fetches the latest published payload from ExamFlow Firestore REST API.
     */
    public static ExamFlowDataSet fetchFromFirestore(String collegeId) throws Exception {
        if (collegeId == null || collegeId.trim().isEmpty()) {
            throw new IllegalArgumentException("College ID is required to fetch from ExamFlow Cloud.");
        }
        String cleanCid = collegeId.trim();
        String url = "https://firestore.googleapis.com/v1/projects/examflow-india/databases/(default)/documents/public_print_queue/" + cleanCid;

        SSLContext unsafeContext = createUnsafeSSLContext();
        HttpClient client = HttpClient.newBuilder().sslContext(unsafeContext).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("ExamFlow Cloud returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String payload = root.path("fields").path("payload").path("stringValue").asText("");
        if (payload.isEmpty()) {
            throw new RuntimeException("ExamFlow document found for " + cleanCid + " but payload is empty.");
        }

        JsonNode seatingData = mapper.readTree(payload);
        ExamFlowDataSet dataSet = parseExamFlowJson(seatingData);
        setCurrentDataSet(dataSet);
        return dataSet;
    }

    private static JsonNode findNode(JsonNode root, String... candidateKeys) {
        if (root == null) return null;
        for (String k : candidateKeys) {
            if (root.has(k)) return root.get(k);
        }
        return null;
    }

    private static String unwrapText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) {
            String text = node.asText();
            if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
                return text.substring(1, text.length() - 1);
            }
            return text;
        }
        return node.asText("");
    }

    private static JsonNode unwrapJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            try {
                String text = node.asText().trim();
                if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
                    return mapper.readTree(text);
                }
            } catch (Exception ignored) {}
        }
        return node;
    }

    private static SSLContext createUnsafeSSLContext() {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
            return ssl;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

