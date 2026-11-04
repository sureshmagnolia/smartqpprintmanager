package com.printmanager;

import com.printmanager.reports.service.ActiveSessionResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ActiveSessionResolverTest {

    @Test
    public void testParseSessionDateTime() {
        LocalDateTime dt1 = ActiveSessionResolver.parseSessionDateTime("04.11.2026 | 09:30 AM");
        assertNotNull(dt1);
        assertEquals(2026, dt1.getYear());
        assertEquals(11, dt1.getMonthValue());
        assertEquals(4, dt1.getDayOfMonth());
        assertEquals(9, dt1.getHour());
        assertEquals(30, dt1.getMinute());

        LocalDateTime dt2 = ActiveSessionResolver.parseSessionDateTime("04-11-2026 | 01:30 PM");
        assertNotNull(dt2);
        assertEquals(13, dt2.getHour());
        assertEquals(30, dt2.getMinute());

        LocalDateTime dt3 = ActiveSessionResolver.parseSessionDateTime("12.05.26 | 12:00 PM");
        assertNotNull(dt3);
        assertEquals(2026, dt3.getYear());
        assertEquals(12, dt3.getHour());
    }

    @Test
    public void testCompareSessions() {
        String s1 = "02.11.2026 | 10:00 AM";
        String s2 = "02.11.2026 | 02:00 PM";
        String s3 = "03.11.2026 | 10:00 AM";

        assertTrue(ActiveSessionResolver.compareSessions(s1, s2) < 0);
        assertTrue(ActiveSessionResolver.compareSessions(s2, s3) < 0);
        assertTrue(ActiveSessionResolver.compareSessions(s3, s1) > 0);
    }

    @Test
    public void testResolveActiveSessionUpcoming() {
        List<String> sessions = Arrays.asList(
                "04.11.2026 | 09:30 AM",
                "04.11.2026 | 01:30 PM",
                "05.11.2026 | 09:30 AM"
        );

        // Current time: 04.11.2026 at 08:30 AM (before morning exam) -> Should select 09:30 AM
        LocalDateTime morningBefore = LocalDateTime.of(2026, 11, 4, 8, 30);
        assertEquals("04.11.2026 | 09:30 AM", ActiveSessionResolver.resolveActiveSession(sessions, morningBefore, 90));

        // Current time: 04.11.2026 at 10:30 AM (during morning exam, within 90-min grace) -> Still 09:30 AM
        LocalDateTime duringMorning = LocalDateTime.of(2026, 11, 4, 10, 30);
        assertEquals("04.11.2026 | 09:30 AM", ActiveSessionResolver.resolveActiveSession(sessions, duringMorning, 90));

        // Current time: 04.11.2026 at 11:15 AM (past 90-min grace for morning) -> Should advance to 01:30 PM
        LocalDateTime midday = LocalDateTime.of(2026, 11, 4, 11, 15);
        assertEquals("04.11.2026 | 01:30 PM", ActiveSessionResolver.resolveActiveSession(sessions, midday, 90));

        // Current time: 06.11.2026 (all sessions past) -> Should fall back to latest session
        LocalDateTime wayPast = LocalDateTime.of(2026, 11, 6, 10, 0);
        assertEquals("05.11.2026 | 09:30 AM", ActiveSessionResolver.resolveActiveSession(sessions, wayPast, 90));
    }
}

