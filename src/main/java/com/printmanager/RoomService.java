package com.printmanager;

import com.printmanager.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RoomService {
    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);

    public String extractQPFromFileName(String fileName) {
        if (fileName == null) return null;
        // Match the standard pattern _143812_ or similar
        Pattern p = Pattern.compile("_(\\d{5,8})_", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(fileName);
        if (m.find()) return m.group(1);
        
        // Fallback for Examflow pattern _14.05.26_FN_143812_
        Pattern p2 = Pattern.compile("_([A-Z0-9]{5,10})[_\\.]", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(fileName);
        if (m2.find()) return m2.group(1);
        
        return null;
    }

    public List<RoomGroup> processRoomWiseJson(JsonNode root, ObservableList<FileItem> fileQueue) {
        Map<String, RoomGroup> groups = new LinkedHashMap<>();
        int nodeIdCounter = 0;
        if (root.isArray()) {
            for (JsonNode node : root) {
                int currentNodeId = nodeIdCounter++;
                String rs = node.path("roomSerial").asText("Unknown");
                String qp = node.path("qpCode").asText("");
                int count = node.path("count").asInt(0);
                String course = node.path("courseName").asText("");
                
                RoomGroup g = groups.computeIfAbsent(rs, RoomGroup::new);
                
                // Track total students per room from JSON nodes
                int totalField = node.path("totalStudents").asInt(0);
                if (totalField == 0) totalField = node.path("totalCount").asInt(0);
                if (totalField == 0) totalField = node.path("roomTotal").asInt(0);
                
                if (totalField > 0) {
                    g.setTotalStudents(totalField);
                } else {
                    g.setTotalStudents(g.getTotalStudents() + count);
                }

                // Match attempt
                boolean matched = false;
                for (FileItem fi : fileQueue) {
                    String extracted = extractQPFromFileName(fi.getFileName());
                    if (qp.equalsIgnoreCase(extracted) || fi.getFileName().contains("_" + qp + "_") || fi.getFileName().contains("_" + qp + ".")) {
                        RoomItem ri = new RoomItem(rs, qp, node.path("pdfFileName").asText(""), count, currentNodeId);
                        ri.setCourseName(course.isEmpty() ? ri.getPdfFileName() : course);
                        ri.setMatchedFile(fi);
                        g.getItems().add(ri);
                        matched = true;
                    }
                }
                
                // If no file matched, still add the entry as Pending
                if (!matched) {
                    RoomItem ri = new RoomItem(rs, qp, node.path("pdfFileName").asText(""), count, currentNodeId);
                    ri.setCourseName(course.isEmpty() ? ri.getPdfFileName() : course);
                    g.getItems().add(ri);
                }
            }
        }
        return new ArrayList<>(groups.values());
    }

    public void relinkRoomItems(ObservableList<RoomGroup> roomGroups, ObservableList<FileItem> fileQueue) {
        logger.info("Relinking Room Items for {} rooms and {} files", roomGroups.size(), fileQueue.size());
        
        for (RoomGroup g : roomGroups) {
            List<RoomItem> existingItems = new ArrayList<>(g.getItems());
            
            // 1. Clear links to files no longer in queue
            for (RoomItem ri : existingItems) {
                if (ri.getMatchedFile() != null && !fileQueue.contains(ri.getMatchedFile())) {
                    ri.setMatchedFile(null);
                    ri.setStatus("Pending");
                }
            }

            // 2. Group existing items by sourceNodeId to identify student sets
            Map<Integer, List<RoomItem>> byNode = existingItems.stream()
                    .filter(ri -> ri.getSourceNodeId() >= 0)
                    .collect(Collectors.groupingBy(RoomItem::getSourceNodeId));

            // Also handle items without node IDs (shouldn't happen with new logic but for safety)
            List<RoomItem> orphans = existingItems.stream()
                    .filter(ri -> ri.getSourceNodeId() < 0)
                    .collect(Collectors.toList());

            List<RoomItem> toAdd = new ArrayList<>();
            List<RoomItem> toRemove = new ArrayList<>();

            // Process each student set
            for (Map.Entry<Integer, List<RoomItem>> entry : byNode.entrySet()) {
                int nid = entry.getKey();
                List<RoomItem> items = entry.getValue();
                RoomItem proto = items.get(0);
                String qp = proto.getQpCode();
                
                List<FileItem> matchingFiles = fileQueue.stream()
                        .filter(fi -> {
                            String fileName = fi.getFileName();
                            String extractedQP = extractQPFromFileName(fileName);
                            return qp.equalsIgnoreCase(extractedQP) || 
                                   fileName.contains("_" + qp + "_") || 
                                   fileName.contains("_" + qp + ".");
                        })
                        .collect(Collectors.toList());

                // Match files for this set
                for (FileItem fi : matchingFiles) {
                    // Already have an item for this file in this set?
                    if (items.stream().anyMatch(ri -> ri.getMatchedFile() == fi)) continue;

                    // Do we have an unmatched item in this set we can use?
                    Optional<RoomItem> nullSlot = items.stream().filter(ri -> ri.getMatchedFile() == null).findFirst();
                    if (nullSlot.isPresent()) {
                        RoomItem slot = nullSlot.get();
                        slot.setMatchedFile(fi);
                        slot.setStatus("Pending");
                    } else {
                        // Create a new split item for this set
                        RoomItem ni = new RoomItem(g.getRoomSerial(), proto.getQpCode(), proto.getPdfFileName(), proto.getCount(), nid);
                        ni.setCourseName(proto.getCourseName());
                        ni.setMatchedFile(fi);
                        ni.setStatus("Pending");
                        toAdd.add(ni);
                        items.add(ni);
                    }
                }
            }
            
            // Re-link orphans (fallback)
            for (RoomItem i : orphans) {
                if (i.getMatchedFile() == null) {
                    for (FileItem fi : fileQueue) {
                        String extracted = extractQPFromFileName(fi.getFileName());
                        if (i.getQpCode().equalsIgnoreCase(extracted) || fi.getFileName().contains("_" + i.getQpCode() + "_")) {
                            i.setMatchedFile(fi);
                            break;
                        }
                    }
                }
            }
            
            if (!toAdd.isEmpty()) g.getItems().addAll(toAdd);

            // 3. Reset Group status
            long pendingCount = g.getItems().stream().filter(ri -> "Pending".equals(ri.getStatus())).count();
            if (pendingCount > 0 || g.getItems().stream().allMatch(ri -> ri.getMatchedFile() == null)) {
                if ("Finished".equals(g.getStatus()) || "Partial Error".equals(g.getStatus()) || g.getStatus() == null || g.getStatus().isEmpty()) {
                    g.setStatus("Ready");
                }
            }
        }
    }
}
