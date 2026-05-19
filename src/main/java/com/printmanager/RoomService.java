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
        if (root.isArray()) {
            for (JsonNode node : root) {
                String rs = node.path("roomSerial").asText("Unknown");
                String qp = node.path("qpCode").asText("");
                RoomGroup g = groups.computeIfAbsent(rs, RoomGroup::new);
                RoomItem i = new RoomItem(rs, qp, node.path("pdfFileName").asText(""), node.path("count").asInt(0));
                
                // Immediate match attempt
                for (FileItem fi : fileQueue) {
                    String extracted = extractQPFromFileName(fi.getFileName());
                    if (qp.equalsIgnoreCase(extracted) || fi.getFileName().contains("_" + qp + "_") || fi.getFileName().contains("_" + qp + ".")) {
                        i.setMatchedFile(fi);
                        break;
                    }
                }
                g.getItems().add(i);
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

            // 2. Group by QP to process each requirement
            Map<String, List<RoomItem>> byQp = existingItems.stream()
                    .collect(Collectors.groupingBy(ri -> ri.getQpCode().toLowerCase()));

            List<RoomItem> toAdd = new ArrayList<>();
            List<RoomItem> toRemove = new ArrayList<>();

            for (Map.Entry<String, List<RoomItem>> entry : byQp.entrySet()) {
                String qp = entry.getKey();
                List<RoomItem> items = entry.getValue();
                
                List<FileItem> matchingFiles = fileQueue.stream()
                        .filter(fi -> {
                            String fileName = fi.getFileName();
                            String extractedQP = extractQPFromFileName(fileName);
                            return qp.equalsIgnoreCase(extractedQP) || 
                                   fileName.contains("_" + qp + "_") || 
                                   fileName.contains("_" + qp + ".");
                        })
                        .collect(Collectors.toList());

                // Prune extra nulls first
                if (items.size() > 1) {
                    List<RoomItem> unmatched = items.stream().filter(ri -> ri.getMatchedFile() == null).collect(Collectors.toList());
                    boolean anyMatched = items.stream().anyMatch(ri -> ri.getMatchedFile() != null);
                    if (anyMatched) {
                        toRemove.addAll(unmatched);
                        items.removeAll(unmatched);
                    } else if (unmatched.size() > 1) {
                        List<RoomItem> extras = unmatched.subList(1, unmatched.size());
                        toRemove.addAll(extras);
                        items.removeAll(extras);
                    }
                }

                // Match files
                for (FileItem fi : matchingFiles) {
                    if (items.stream().anyMatch(ri -> ri.getMatchedFile() == fi)) continue;

                    Optional<RoomItem> nullSlot = items.stream().filter(ri -> ri.getMatchedFile() == null).findFirst();
                    if (nullSlot.isPresent()) {
                        RoomItem slot = nullSlot.get();
                        slot.setMatchedFile(fi);
                        slot.setStatus("Pending");
                    } else {
                        RoomItem t = items.get(0);
                        RoomItem ni = new RoomItem(g.getRoomSerial(), t.getQpCode(), t.getPdfFileName(), t.getCount());
                        ni.setCourseName(t.getCourseName());
                        ni.setMatchedFile(fi);
                        ni.setStatus("Pending");
                        toAdd.add(ni);
                        items.add(ni);
                    }
                }
            }
            
            if (!toRemove.isEmpty()) g.getItems().removeAll(toRemove);
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
