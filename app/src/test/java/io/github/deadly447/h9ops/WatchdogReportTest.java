package io.github.deadly447.h9ops;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class WatchdogReportTest {
    private static byte[] p(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test public void parsesCountersAndUptime() {
        // 2 restarts, last from state 5, no stalls, stage 0; ticks 0x00012345 = 74565 -> 745 s
        WatchdogReport w = WatchdogReport.parse(p("H9PW0205000045230100"));
        assertNotNull(w);
        assertEquals(2, w.restarts);
        assertEquals(5, w.lastState);
        assertFalse(w.lastStale);
        assertEquals(0, w.stalls);
        assertEquals(745, w.uptimeS);
        String d = w.describe();
        assertTrue(d, d.startsWith("radio on for 12 min; Bluetooth watchdog restarts since power-on: 2"));
        assertTrue(d, d.contains("not advertising, disconnected"));
    }

    @Test public void staleConnectionFlag() {
        WatchdogReport w = WatchdogReport.parse(p("H9PW01A30201FFFFFF00"));
        assertNotNull(w);
        assertTrue(w.lastStale);
        assertEquals(35, w.lastState);
        assertEquals(2, w.stalls);
        assertEquals(1, w.stage);
        assertEquals(0xFFFFFFL / 100, w.uptimeS);
        assertTrue(w.describe().contains("dropped a connection"));
        assertTrue(w.describe().contains("a disconnect is under way"));
    }

    @Test public void rejectsOtherFrames() {
        assertNull(WatchdogReport.parse(p("H9PRK")));
        assertNull(WatchdogReport.parse(p("H9PW02050000452301")));      // too short
        assertNull(WatchdogReport.parse(p("H9PW0205000045230G00")));    // not hex
        assertNull(WatchdogReport.parse(p("H9PX0205000045230100")));
        assertNull(WatchdogReport.parse(null));
    }

    @Test public void durations() {
        assertEquals("59 s", WatchdogReport.duration(59));
        assertEquals("2 min", WatchdogReport.duration(150));
        assertEquals("3 h 5 min", WatchdogReport.duration(3 * 3600 + 5 * 60 + 9));
    }
}
