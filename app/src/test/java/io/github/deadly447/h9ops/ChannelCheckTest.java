package io.github.deadly447.h9ops;

import static org.junit.Assert.*;

import org.junit.Test;

public class ChannelCheckTest {
    private static final long H = 60L * 60 * 1000, M = 60L * 1000;

    @Test public void remindsOnFirstConnectAndManualConnect() {
        assertTrue(ChannelCheck.remind(true, 10 * H, 0, false));
        assertTrue(ChannelCheck.remind(true, 10 * H, 10 * H - M, true));
        assertFalse(ChannelCheck.remind(false, 10 * H, 0, true));
    }

    @Test public void autoReconnectsRemindAtMostEvery8Hours() {
        assertFalse(ChannelCheck.remind(true, 10 * H, 9 * H, false));
        assertTrue(ChannelCheck.remind(true, 18 * H, 10 * H, false));
        assertTrue(ChannelCheck.remind(true, 5 * H, 10 * H, false));   // clock went backwards
    }

    @Test public void hintAfter15QuietMinutes() {
        long c = 100 * H;
        assertNull(ChannelCheck.quietHint(c + 14 * M, c, 0));
        String h = ChannelCheck.quietHint(c + 16 * M, c, 0);
        assertNotNull(h);
        assertTrue(h.startsWith("Nothing heard for 16 min"));
        assertTrue(h.contains("Wide"));
    }

    @Test public void hintSaysWhenTheRadioConfirmedChannelB() {
        long c = 100 * H;
        String h = ChannelCheck.quietHint(c + 20 * M, c, 0, true);
        assertTrue(h, h.contains("channel B is set right"));
        assertFalse(ChannelCheck.quietHint(c + 20 * M, c, 0, false).contains("set right"));
    }

    @Test public void hintClearsWhenSomethingIsHeard() {
        long c = 100 * H;
        assertNull(ChannelCheck.quietHint(c + 30 * M, c, c + 20 * M));
        assertNotNull(ChannelCheck.quietHint(c + 40 * M, c, c + 20 * M));
        assertNull(ChannelCheck.quietHint(c + 40 * M, 0, 0));   // not connected
    }
}
