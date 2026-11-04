package com.printmanager.reports.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses and sorts ExamFlow session strings ("DD.MM.YYYY | HH:MM AM/PM")
 * and resolves the immediately upcoming/active session based on system clock.
 */
public class ActiveSessionResolver {
    private static final Logger logger = LoggerFactory.getLogger(ActiveSessionResolver.class);
    public static final int DEFAULT_GRACE_MINUTES = 90; // Keep session active 90 mins after official start

    /**
     * Parses a session string like "04.11.2026 | 09:30 AM" or "04-11-2026 | 01:30 PM"
     * into a LocalDateTime object. Returns null if invalid.
     */
    public static LocalDateTime parseSessionDateTime(String sessionStr) {
        if (sessionStr == null || sessionStr.trim().isEmpty()) return null;
        try {
            String[] parts = sessionStr.contains("|") ? sessionStr.split("\\|") : new String[]{sessionStr, ""};
            String datePart = parts[0].trim().replace('-', '.').replace('/', '.');
            String timePart = parts.length > 1 ? parts[1].trim() : "";

            String[] dParts = datePart.split("\\.");
            if (dParts.length < 3) return null;

            int day = Integer.parseInt(dParts[0].trim());
            int month = Integer.parseInt(dParts[1].trim());
            int year = Integer.parseInt(dParts[2].trim());
            if (year < 100) year += 2000;

            LocalDate date = LocalDate.of(year, month, day);

            LocalTime time = LocalTime.of(9, 30); // Default fallback
            if (!timePart.isEmpty()) {
                String[] tSplit = timePart.split("\\s+");
                String[] hm = tSplit[0].split(":");
                int hour = Integer.parseInt(hm[0].trim());
                int minute = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
                
                if (tSplit.length > 1) {
                    String ampm = tSplit[1].trim().toUpperCase();
                    if ("PM".equals(ampm) && hour < 12) hour += 12;
                    else if ("AM".equals(ampm) && hour == 12) hour = 0;
                }
                time = LocalTime.of(hour, minute);
            }
            return LocalDateTime.of(date, time);
        } catch (Exception e) {
            logger.debug("Failed to parse session string: '{}': {}", sessionStr, e.getMessage());
            return null;
        }
    }

    /**
     * Compares two session strings chronologically, matching ExamFlow's compareSessionStrings.
     */
    public static int compareSessions(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        LocalDateTime dtA = parseSessionDateTime(a);
        LocalDateTime dtB = parseSessionDateTime(b);

        if (dtA != null && dtB != null) {
            return dtA.compareTo(dtB);
        }
        if (dtA != null) return 1;
        if (dtB != null) return -1;
        return a.compareToIgnoreCase(b);
    }

    /**
     * Resolves the active session from a list of session strings.
     * Selects the immediately upcoming session, with a grace period for ongoing exams.
     * Falls back to the latest session if all are in the past.
     */
    public static String resolveActiveSession(List<String> sessions, LocalDateTime now, int graceMinutes) {
        if (sessions == null || sessions.isEmpty()) return null;
        if (sessions.size() == 1) return sessions.get(0);

        List<String> sortedSessions = new ArrayList<>(sessions);
        sortedSessions.sort(ActiveSessionResolver::compareSessions);

        LocalDateTime threshold = now.minusMinutes(graceMinutes);

        // Find the first session starting on or after (now - graceMinutes)
        for (String session : sortedSessions) {
            LocalDateTime sdt = parseSessionDateTime(session);
            if (sdt != null && !sdt.isBefore(threshold)) {
                return session;
            }
        }

        // If all sessions are earlier than threshold, return the latest past session
        return sortedSessions.get(sortedSessions.size() - 1);
    }

    public static String resolveActiveSession(List<String> sessions) {
        return resolveActiveSession(sessions, LocalDateTime.now(), DEFAULT_GRACE_MINUTES);
    }
}

