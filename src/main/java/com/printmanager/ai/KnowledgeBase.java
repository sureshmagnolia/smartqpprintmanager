package com.printmanager.ai;

import java.util.HashMap;
import java.util.Map;

public class KnowledgeBase {
    // Mapping: Normalized Subject + Course -> Ground Truth QP
    private static final Map<String, String> identityMap = new HashMap<>();

    public static void train(String subject, String courseCode, String qpCode) {
        if (subject == null || qpCode == null || qpCode.isEmpty()) return;
        String key = normalize(subject) + "|" + normalize(courseCode);
        identityMap.put(key, qpCode);
    }

    public static String getLearnedQP(String subject, String courseCode) {
        String key = normalize(subject) + "|" + normalize(courseCode);
        return identityMap.get(key);
    }

    private static String normalize(String input) {
        if (input == null) return "";
        return input.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
    
    public static void clear() { identityMap.clear(); }
}
