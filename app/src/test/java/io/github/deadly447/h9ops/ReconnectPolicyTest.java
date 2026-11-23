package io.github.deadly447.h9ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPolicyTest {

    @Test
    public void waitsDoubleUpToThirtySeconds() {
        ReconnectPolicy p = new ReconnectPolicy();
        assertEquals(3_000, p.nextDelay());
        assertEquals(6_000, p.nextDelay());
        assertEquals(12_000, p.nextDelay());
        assertEquals(24_000, p.nextDelay());
        assertEquals(30_000, p.nextDelay());
        assertEquals(30_000, p.nextDelay());
    }

    @Test
    public void silentRadioStopsDirectConnectsAndSlowsDown() {
        // the 2026-11-21 log: after the drop no scan saw the radio for 28 minutes
        ReconnectPolicy p = new ReconnectPolicy();
        assertTrue(p.scanMissed());
        p.nextDelay();
        assertTrue(p.scanMissed());
        p.nextDelay();
        assertFalse("third miss: the radio isn't advertising", p.scanMissed());
        assertTrue(p.silent());
        assertEquals(ReconnectPolicy.SILENT_MS, p.nextDelay());
        assertFalse(p.scanMissed());
        assertEquals(ReconnectPolicy.SILENT_MS, p.nextDelay());
    }

    @Test
    public void seenAgainEndsSilenceAndResetsTheWait() {
        ReconnectPolicy p = new ReconnectPolicy();
        for (int i = 0; i < 5; i++) { p.scanMissed(); p.nextDelay(); }
        assertTrue(p.silent());
        assertTrue("reports that it had been silent", p.seen());
        assertFalse(p.silent());
        p.connected();
        assertEquals(3_000, p.nextDelay());
        assertFalse(p.seen());
    }

    @Test
    public void phoneWhereOnlyDirectConnectsWorkKeepsThem() {
        // older firmware on some phones: scans never see the radio, a direct connect does
        ReconnectPolicy p = new ReconnectPolicy();
        assertTrue(p.scanMissed());
        p.connected();
        assertTrue(p.directWorks());
        for (int i = 0; i < 10; i++) assertTrue(p.scanMissed());
        assertFalse(p.silent());
        assertEquals(3_000, p.nextDelay());
    }

    @Test
    public void connectAfterAFoundScanDoesNotCountAsDirect() {
        ReconnectPolicy p = new ReconnectPolicy();
        p.scanMissed();
        p.seen();
        p.connected();
        assertFalse(p.directWorks());
    }

    @Test
    public void restartClearsSilence() {
        ReconnectPolicy p = new ReconnectPolicy();
        for (int i = 0; i < 4; i++) p.scanMissed();
        assertTrue(p.silent());
        p.restart();
        assertFalse(p.silent());
        assertEquals(0, p.missed());
        assertEquals(3_000, p.nextDelay());
    }

    @Test
    public void status147IsNamed() {
        assertTrue(DevLog.gattStatus(147).startsWith("147 connection attempt timed out"));
        assertEquals("999", DevLog.gattStatus(999));
    }
}
